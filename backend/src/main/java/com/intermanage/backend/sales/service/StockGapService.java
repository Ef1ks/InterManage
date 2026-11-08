package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.dto.StockGapKind;
import com.intermanage.backend.sales.dto.StockGapReport;
import com.intermanage.backend.sales.dto.StockGapReport.Item;
import com.intermanage.backend.sales.dto.StockGapRequest;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.StockGapRepository;
import com.intermanage.backend.sales.repository.StockGapRepository.ArticleReceipts;
import com.intermanage.backend.sales.repository.StockGapRepository.SellingArticle;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Braki towaru: artykuły, które regularnie się sprzedają, a skończyły się albo zaraz skończą. Sam niski stan nie
 * wystarcza: stany bywają ujemne przy towarze na półce, więc o braku decyduje też cisza w sprzedaży, mierzona w
 * sztukach, które powinny były zejść ({@link #MISSED_FOR_SILENCE}), a nie w dniach: dla hitu 2 dni to już brak, dla
 * artykułu z 3 szt. na tydzień jeszcze przypadek.
 *
 * <p>Progi sprawdzone na sprzedaży i odtworzonych stanach 10023 z października 2026: z braków bez dostawy od 14 dni w
 * 84% nic się nie sprzedało przez kolejne dwa tygodnie albo do dostawy, przy samym stanie ≤ 1 i średniej z 4 tygodni
 * było to 56%.
 */
@Service
@Transactional(readOnly = true)
public class StockGapService {

    public static final BigDecimal DEFAULT_MIN_WEEKLY = new BigDecimal("3");
    public static final BigDecimal DEFAULT_MAX_STOCK = BigDecimal.ONE;
    public static final int DEFAULT_NO_DELIVERY_DAYS = 14;

    /** Ile sztuk musiało się nie sprzedać, żeby cisza była brakiem; poniżej to często przypadek. */
    private static final BigDecimal MISSED_FOR_SILENCE = new BigDecimal("3");
    /** Tyle tygodni ze sprzedażą z 8 ostatnich, żeby to była stała sprzedaż, a nie jednorazowa akcja. */
    private static final int MIN_WEEKS_SOLD = 4;
    /** Zapas na tyle dni to „kończy się”. */
    private static final BigDecimal LOW_COVER_DAYS = new BigDecimal("3");
    /** Mniej dostaw z tego okresu przy towarze spoza cadencier oznacza zakup jednorazowy. */
    private static final int REGULAR_DELIVERIES = 3;
    private static final int REGULAR_DAYS = 210;
    /** Mięso, garmaż i wyroby własne: stan nie mówi tu, ile jest towaru, bo schodzi na produkcję. */
    private static final List<String> PRODUCTION_REGIONS = List.of("24", "36", "46");

    private static final BigDecimal DAYS_IN_WEEK = BigDecimal.valueOf(7);
    private static final int QUANTITY_SCALE = 3;
    private static final int COVER_SCALE = 1;
    private static final int MONEY_SCALE = 2;

    private final StockGapRepository gaps;
    private final SaleRepository sales;
    private final ProductImportRepository productImports;
    private final StoreRepository stores;

    public StockGapService(StockGapRepository gaps, SaleRepository sales, ProductImportRepository productImports,
            StoreRepository stores) {
        this.gaps = gaps;
        this.sales = sales;
        this.productImports = productImports;
        this.stores = stores;
    }

    public StockGapReport report(UUID storeId, StockGapRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        BigDecimal minWeekly = request.minWeekly() != null ? request.minWeekly() : DEFAULT_MIN_WEEKLY;
        BigDecimal maxStock = request.maxStock() != null ? request.maxStock() : DEFAULT_MAX_STOCK;
        int noDeliveryDays = request.noDeliveryDays() != null ? request.noDeliveryDays() : DEFAULT_NO_DELIVERY_DAYS;
        if (minWeekly.signum() <= 0) {
            throw ApiException.badRequest("Sprzedaż w tygodniu musi być większa od zera.");
        }
        if (maxStock.signum() < 0) {
            throw ApiException.badRequest("Stan nie może być ujemny.");
        }
        if (noDeliveryDays < 0) {
            throw ApiException.badRequest("Liczba dni bez dostawy nie może być ujemna.");
        }

        LocalDate lastSaleDate = gaps.findLastSaleDate(storeId).orElse(null);
        List<Item> items = new ArrayList<>();
        if (lastSaleDate != null) {
            sales.tuneForReport();
            List<String> nomenclatures = request.nomenclatures() != null ? request.nomenclatures() : List.of();
            Map<String, ArticleReceipts> receipts = gaps
                    .findReceipts(storeId, lastSaleDate.minusDays(REGULAR_DAYS).atStartOfDay()).stream()
                    .collect(Collectors.toMap(ArticleReceipts::getItm8, Function.identity()));
            for (SellingArticle article : gaps.findSellingArticles(storeId, lastSaleDate, MIN_WEEKS_SOLD,
                    minWeekly.doubleValue(), nomenclatures.toArray(String[]::new))) {
                if (isGoods(article.getSubfamilyCode())) {
                    Item item = item(article, receipts.get(article.getItm8()), lastSaleDate, maxStock);
                    if (item != null && isReported(item, lastSaleDate, noDeliveryDays)) {
                        items.add(item);
                    }
                }
            }
        }
        items.sort(Comparator.comparing(Item::weeklyMargin, Comparator.reverseOrder()).thenComparing(Item::itm8));

        return new StockGapReport(store.getNumber(), store.getName(),
                productImports.findNewestReportGeneratedAt(storeId).orElse(null), lastSaleDate, minWeekly, maxStock,
                noDeliveryDays, items);
    }

    private static boolean isGoods(String subfamilyCode) {
        return !RegionGroups.isNotGoods(subfamilyCode)
                && (subfamilyCode == null || PRODUCTION_REGIONS.stream().noneMatch(subfamilyCode::startsWith));
    }

    /** Artykuł z zapasem i normalną sprzedażą nie ma rodzaju, więc do raportu nie trafia. */
    private static Item item(SellingArticle article, ArticleReceipts receipts, LocalDate lastSaleDate,
            BigDecimal maxStock) {
        BigDecimal stock = article.getStock() != null ? article.getStock() : BigDecimal.ZERO;
        BigDecimal typicalWeek = article.getTypicalWeek();
        long silentDays = ChronoUnit.DAYS.between(article.getLastSale(), lastSaleDate);
        BigDecimal missed = typicalWeek.multiply(BigDecimal.valueOf(silentDays))
                .divide(DAYS_IN_WEEK, QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cover = stock.signum() <= 0 ? null
                : stock.multiply(DAYS_IN_WEEK).divide(typicalWeek, COVER_SCALE, RoundingMode.HALF_UP);
        int recentDeliveries = receipts != null ? receipts.getRecentDays() : 0;
        StockGapKind kind = kind(article, stock, missed, cover, recentDeliveries, maxStock);
        if (kind == null) {
            return null;
        }
        BigDecimal weeklyMargin = article.getSoldQuantity().signum() == 0 ? BigDecimal.ZERO
                : article.getMarginValue().multiply(typicalWeek)
                        .divide(article.getSoldQuantity(), MONEY_SCALE, RoundingMode.HALF_UP);
        return new Item(kind, article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                article.getSubfamilyCode(), article.getCadencier(), stock, typicalWeek, article.getWeeksSold(),
                article.getLastSale(), silentDays, missed, cover, weeklyMargin,
                receipts != null && receipts.getLastReceipt() != null ? receipts.getLastReceipt().toLocalDate() : null,
                recentDeliveries, receipts != null ? receipts.getSupplierCode() : null,
                receipts != null ? receipts.getSupplierName() : null);
    }

    private static StockGapKind kind(SellingArticle article, BigDecimal stock, BigDecimal missed, BigDecimal cover,
            int recentDeliveries, BigDecimal maxStock) {
        if (missed.compareTo(MISSED_FOR_SILENCE) >= 0) {
            if (stock.compareTo(maxStock) > 0) {
                return null;
            }
            return article.getCadencier() || recentDeliveries >= REGULAR_DELIVERIES ? StockGapKind.MISSING
                    : StockGapKind.ONE_OFF;
        }
        if (stock.signum() <= 0) {
            return StockGapKind.WRONG_STOCK;
        }
        return cover.compareTo(LOW_COVER_DAYS) <= 0 ? StockGapKind.RUNNING_OUT : null;
    }

    /**
     * Warunek dostawy dotyczy tylko braków: po co pytać o artykuł, do którego dopiero co coś przyjechało. Towar, który
     * się kończy, jest szybki i dostawy ma częste, a przy złym stanie dostawa niczego nie zmienia.
     */
    private static boolean isReported(Item item, LocalDate lastSaleDate, int noDeliveryDays) {
        boolean gap = Set.of(StockGapKind.MISSING, StockGapKind.ONE_OFF).contains(item.kind());
        return !gap || item.lastDeliveryDate() == null
                || ChronoUnit.DAYS.between(item.lastDeliveryDate(), lastSaleDate) >= noDeliveryDays;
    }
}
