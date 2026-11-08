package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.dto.NegativeStockDelivery;
import com.intermanage.backend.sales.dto.NegativeStockReport;
import com.intermanage.backend.sales.dto.NegativeStockReport.Item;
import com.intermanage.backend.sales.dto.NegativeStockRequest;
import com.intermanage.backend.sales.repository.NegativeStockRepository;
import com.intermanage.backend.sales.repository.NegativeStockRepository.ArticleReceipts;
import com.intermanage.backend.sales.repository.NegativeStockRepository.NegativeArticle;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.StockGapRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ujemne stany: artykuły, których stan zszedł poniżej zera. Stan schodzi na minus, gdy towar się sprzedaje, a nie
 * przyjechał pod tym kodem: zwykle dostawa weszła pod innym artykułem (zły EAN przy przyjęciu albo na półce) albo
 * kasa sprzedaje pod złym kodem. Domyślnie tylko artykuły bez dostawy od 14 dni, bo przy świeżej dostawie minus
 * bywa chwilowy (towar sprzedany przed wpisaniem PZ).
 */
@Service
@Transactional(readOnly = true)
public class NegativeStockService {

    public static final int DEFAULT_DAYS = 14;
    /** Okres kolumny sprzedaży, licząc wstecz od ostatniego dnia z wgraną sprzedażą. */
    public static final int SALES_DAYS = 28;
    /** Mięso, garmaż i wyroby własne: stany schodzą na minus przez produkcję, nie przez złe kody. */
    private static final List<String> PRODUCTION_REGIONS = List.of("24", "36", "46");
    private static final int MONEY_SCALE = 2;

    private final NegativeStockRepository negatives;
    private final StockGapRepository gaps;
    private final SaleRepository sales;
    private final ProductImportRepository productImports;
    private final StoreRepository stores;

    public NegativeStockService(NegativeStockRepository negatives, StockGapRepository gaps, SaleRepository sales,
            ProductImportRepository productImports, StoreRepository stores) {
        this.negatives = negatives;
        this.gaps = gaps;
        this.sales = sales;
        this.productImports = productImports;
        this.stores = stores;
    }

    public NegativeStockReport report(UUID storeId, NegativeStockRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        NegativeStockDelivery delivery = request.delivery() != null ? request.delivery()
                : NegativeStockDelivery.NO_DELIVERY_SINCE;
        int days = request.days() != null ? request.days() : DEFAULT_DAYS;
        if (days < 1) {
            throw ApiException.badRequest("Liczba dni bez dostawy musi być większa od zera.");
        }
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        LocalDate from = null;
        LocalDate to = null;
        if (delivery == NegativeStockDelivery.DELIVERED_IN) {
            if (request.from() == null || request.to() == null) {
                throw ApiException.badRequest("Podaj okres dostaw.");
            }
            from = request.from();
            to = request.to();
            ReportPeriod.check(from, to, ReportPeriod.MAX_DAYS, "Okres dostaw");
        }
        boolean withProduction = Boolean.TRUE.equals(request.withProduction());
        List<String> nomenclatures = request.nomenclatures() != null ? request.nomenclatures() : List.of();

        sales.tuneForReport();
        LocalDate lastSaleDate = gaps.findLastSaleDate(storeId).orElse(null);
        LocalDate salesTo = lastSaleDate != null ? lastSaleDate : today;
        LocalDate salesFrom = salesTo.minusDays(SALES_DAYS - 1L);
        LocalDate periodFrom = from != null ? from : today;
        LocalDate periodTo = to != null ? to.plusDays(1) : today;
        Map<String, ArticleReceipts> receipts = negatives
                .findReceipts(storeId, periodFrom.atStartOfDay(), periodTo.atStartOfDay()).stream()
                .collect(Collectors.toMap(ArticleReceipts::getItm8, Function.identity()));

        List<Item> items = new ArrayList<>();
        for (NegativeArticle article : negatives.findArticles(storeId, salesFrom, salesTo,
                nomenclatures.toArray(String[]::new))) {
            if (!isReported(article.getSubfamilyCode(), withProduction || !nomenclatures.isEmpty())) {
                continue;
            }
            Item item = item(article, receipts.get(article.getItm8()), today);
            if (matches(item, delivery, days)) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(Item::stockValue).thenComparing(Item::stock).thenComparing(Item::itm8));

        return new NegativeStockReport(store.getNumber(), store.getName(),
                productImports.findNewestReportGeneratedAt(storeId).orElse(null), lastSaleDate, salesFrom, today,
                delivery, days, from, to, items);
    }

    /** Torby, kaucje i usługi nie są towarem; produkcja tylko na życzenie albo przy wybranej nomenklaturze. */
    private static boolean isReported(String subfamilyCode, boolean withProduction) {
        return !RegionGroups.isNotGoods(subfamilyCode) && (withProduction || subfamilyCode == null
                || PRODUCTION_REGIONS.stream().noneMatch(subfamilyCode::startsWith));
    }

    private static Item item(NegativeArticle article, ArticleReceipts receipts, LocalDate today) {
        BigDecimal stock = article.getStock();
        BigDecimal value = article.getPurchasePrice() == null ? BigDecimal.ZERO
                : stock.multiply(article.getPurchasePrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        LocalDate lastDelivery = receipts != null && receipts.getLastReceipt() != null
                ? receipts.getLastReceipt().toLocalDate()
                : null;
        return new Item(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                article.getSubfamilyCode(), article.getCadencier(), article.getActive(), stock, value,
                article.getSoldQuantity() != null ? article.getSoldQuantity() : BigDecimal.ZERO,
                article.getSoldGross() != null ? article.getSoldGross() : BigDecimal.ZERO, article.getLastSale(),
                lastDelivery, lastDelivery != null ? ChronoUnit.DAYS.between(lastDelivery, today) : null,
                receipts != null ? receipts.getDeliveryDays() : 0, receipts != null ? receipts.getPeriodDays() : 0,
                receipts != null ? receipts.getSupplierCode() : null,
                receipts != null ? receipts.getSupplierName() : null);
    }

    private static boolean matches(Item item, NegativeStockDelivery delivery, int days) {
        return switch (delivery) {
            case NO_DELIVERY_SINCE -> item.daysSinceDelivery() == null || item.daysSinceDelivery() >= days;
            case NEVER -> item.lastDeliveryDate() == null;
            case DELIVERED_IN -> item.periodDeliveries() > 0;
            case ANY -> true;
        };
    }
}
