package com.intermanage.backend.pricecheck.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.Margin;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.nomenclature.service.NomenclatureMarginService;
import com.intermanage.backend.pricecheck.component.ExcludedSuppliers;
import com.intermanage.backend.pricecheck.dto.ArticleMarginReport;
import com.intermanage.backend.pricecheck.dto.ArticleMarginReport.Item;
import com.intermanage.backend.pricecheck.dto.ArticleMarginReport.PromoSource;
import com.intermanage.backend.pricecheck.dto.ArticleMarginReport.PurchaseSource;
import com.intermanage.backend.pricecheck.dto.ArticleMarginVerificationRequest;
import com.intermanage.backend.pricecheck.dto.ReportSupplier;
import com.intermanage.backend.pricecheck.entity.ArticleMarginVerification;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.ArticleMarginVerificationRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.repository.ArticleMarginRepository;
import com.intermanage.backend.pricecheck.repository.ArticleMarginRepository.ArticleMargin;
import com.intermanage.backend.sales.repository.SaleRepository;

/**
 * Marże artykułów, które żyją (sprzedaż albo przyjęcie PZ w ostatnich {@value #SOLD_MONTHS} miesiącach;
 * aktywność i stan z kartoteki nic nie mówią), z aktualnych cen, a nie z przyjęć: artykuły z marżą poniżej wybranego procentu (np.
 * 25, 20, 15%; marża wymagana na nomenklaturze tylko do porównania) i aktualne promocje z marżą na cenie promocyjnej. Cena zakupu z ostatniego
 * przyjęcia PZ z ostatniego roku ({@value #PURCHASE_LOOKBACK_DAYS} dni), bez niego z kartoteki. Dostawców z
 * {@link ExcludedSuppliers} i tych bez marży wymaganej (lista z raportu marż poza normą) raporty pomijają (po dostawcy
 * ostatniego przyjęcia). Promocje z gazetki (utrzymanie cen z centrali) nie trafiają do raportu promocji, bo sklep nie
 * ma na nie wpływu.
 */
@Service
public class ArticleMarginService {

    static final int PURCHASE_LOOKBACK_DAYS = 365;
    /** Artykuł „żyje”, gdy sprzedał się w tylu ostatnich miesiącach kalendarzowych (z bieżącym) albo miał przyjęcie. */
    static final int SOLD_MONTHS = 3;
    /** Domyślnie raport pokazuje artykuły z marżą poniżej tylu procent. */
    static final int DEFAULT_MAX_MARGIN = 20;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ArticleMarginRepository articles;
    private final NomenclatureMarginService margins;
    private final ExcludedSuppliers excludedSuppliers;
    private final ReportExcludedSupplierService reportSuppliers;
    private final SaleRepository sales;
    private final ArticleMarginVerificationRepository verifications;
    private final PriceCheckMapper mapper;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public ArticleMarginService(ArticleMarginRepository articles, NomenclatureMarginService margins,
            ExcludedSuppliers excludedSuppliers,
            ReportExcludedSupplierService reportSuppliers, SaleRepository sales,
            ArticleMarginVerificationRepository verifications, PriceCheckMapper mapper, StoreRepository stores,
            AppUserRepository users) {
        this.verifications = verifications;
        this.mapper = mapper;
        this.stores = stores;
        this.users = users;
        this.articles = articles;
        this.margins = margins;
        this.excludedSuppliers = excludedSuppliers;
        this.reportSuppliers = reportSuppliers;
        this.sales = sales;
    }

    /** Artykuły z marżą poniżej {@code maxMargin} % (bez niego {@value #DEFAULT_MAX_MARGIN}%), od najniższej. */
    @Transactional(readOnly = true)
    public ArticleMarginReport lowMargins(UUID storeId, BigDecimal maxMargin) {
        if (maxMargin != null && (maxMargin.signum() < 0 || maxMargin.compareTo(HUNDRED) > 0)) {
            throw ApiException.badRequest("Marża musi mieć od 0 do 100%.");
        }
        BigDecimal threshold = maxMargin != null ? maxMargin : BigDecimal.valueOf(DEFAULT_MAX_MARGIN);
        Context context = context(storeId);
        Map<String, ArticleMarginVerification> verified = new HashMap<>();
        verifications.findActive(storeId).forEach(verification -> verified.put(verification.getItm8(), verification));
        List<Item> items = new ArrayList<>();
        for (ArticleMargin article : context.rows()) {
            if (context.excluded().contains(article.getSupplierCode())) {
                continue;
            }
            Item item = item(article, context.required());
            if (item != null && item.marginPercent().compareTo(threshold) < 0) {
                ArticleMarginVerification verification = verified.get(item.itm8());
                items.add(verification != null && verification.covers(item.salePrice(), item.purchasePrice(),
                        item.lastPurchaseDate())
                        ? item.withVerification(mapper.toResponse(verification)) : item);
            }
        }
        items.sort(Comparator.comparing(Item::marginPercent).thenComparing(Item::itm8));
        return new ArticleMarginReport(context.today(), threshold, !context.required().isEmpty(), 0,
                context.withoutMargin(), items);
    }

    /**
     * Oznacza artykuły jako sprawdzone przy cenach, które widział sprawdzający; poprzednia weryfikacja artykułu dostaje
     * znacznik. Zwraca id nowych wpisów (do „Cofnij”).
     */
    @Transactional
    public List<UUID> verify(UUID storeId, ArticleMarginVerificationRequest request, UUID userId) {
        Store store = stores.getReferenceById(storeId);
        AppUser user = users.getReferenceById(userId);
        Map<String, ArticleMarginVerificationRequest.Entry> byItm8 = new LinkedHashMap<>();
        request.items().forEach(entry -> byItm8.put(entry.itm8(), entry));
        verifications.findActive(storeId, byItm8.keySet()).forEach(old -> old.cancel(user));
        verifications.flush();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        List<UUID> ids = new ArrayList<>();
        for (ArticleMarginVerificationRequest.Entry entry : byItm8.values()) {
            ids.add(verifications.save(new ArticleMarginVerification(store, entry.itm8(), entry.salePrice(),
                    entry.purchasePrice(), entry.marginPercent(), entry.lastPurchaseDate(), note, user)).getId());
        }
        return ids;
    }

    /** Cofa weryfikacje (znacznik); artykuły wracają do kontroli. */
    @Transactional
    public void cancel(UUID storeId, List<UUID> ids, UUID userId) {
        AppUser user = users.getReferenceById(userId);
        verifications.findInStore(storeId, ids).stream().filter(v -> v.getCancelledAt() == null)
                .forEach(verification -> verification.cancel(user));
    }

    /** Przywraca cofnięte weryfikacje, o ile artykuł nie ma w międzyczasie nowszej. */
    @Transactional
    public void restore(UUID storeId, List<UUID> ids) {
        List<ArticleMarginVerification> cancelled = verifications.findInStore(storeId, ids).stream()
                .filter(v -> v.getCancelledAt() != null).toList();
        if (cancelled.isEmpty()) {
            return;
        }
        Set<String> active = new HashSet<>();
        verifications.findActive(storeId, cancelled.stream().map(ArticleMarginVerification::getItm8).toList())
                .forEach(verification -> active.add(verification.getItm8()));
        for (ArticleMarginVerification verification : cancelled) {
            if (active.add(verification.getItm8())) {
                verification.restore();
            }
        }
    }

    /** Promocje trwające dziś (bez gazetki) z marżą na cenie promocyjnej, od najniższej marży. */
    @Transactional(readOnly = true)
    public ArticleMarginReport currentPromotions(UUID storeId) {
        Context context = context(storeId);
        List<Item> items = new ArrayList<>();
        int leaflet = 0;
        for (ArticleMargin article : context.rows()) {
            BigDecimal promo = article.getPromoPrice();
            if (promo == null || promo.signum() <= 0 || promo.compareTo(article.getRegularPrice()) >= 0
                    || context.excluded().contains(article.getSupplierCode())) {
                continue;
            }
            if (article.getLeaflet()) {
                leaflet++;
                continue;
            }
            Item item = item(article, context.required());
            if (item != null) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(Item::promoMargin).thenComparing(Item::itm8));
        return new ArticleMarginReport(context.today(), null, !context.required().isEmpty(), leaflet,
                context.withoutMargin(), items);
    }

    private record Context(LocalDate today, Map<String, BigDecimal> required,
            List<ReportSupplier> withoutMargin, Set<String> excluded, List<ArticleMargin> rows) {
    }

    private Context context(UUID storeId) {
        sales.tuneForReport();
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        List<ReportSupplier> withoutMargin = reportSuppliers.active(storeId, ReviewedReport.LOW_MARGIN);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        withoutMargin.forEach(supplier -> excluded.add(supplier.code()));
        List<ArticleMargin> rows = articles.findArticleMargins(storeId,
                today.minusDays(PURCHASE_LOOKBACK_DAYS).atStartOfDay(),
                today.minusMonths(SOLD_MONTHS).atStartOfDay(),
                today.withDayOfMonth(1).minusMonths(SOLD_MONTHS - 1L), today, today, today);
        return new Context(today, margins.effectiveByCode(storeId), withoutMargin, excluded, rows);
    }

    /** Artykuł z marżami; {@code null}, gdy brakuje ceny zakupu albo stawki VAT. */
    private static Item item(ArticleMargin article, Map<String, BigDecimal> required) {
        BigDecimal delivered = article.getLastPurchasePrice();
        boolean fromDelivery = delivered != null && delivered.signum() > 0;
        BigDecimal purchase = fromDelivery ? delivered : article.getCatalogPurchasePrice();
        BigDecimal regular = article.getRegularPrice();
        if (purchase == null || purchase.signum() <= 0 || article.getVatRate() == null || regular == null
                || regular.signum() <= 0) {
            return null;
        }
        purchase = purchase.setScale(4, RoundingMode.HALF_UP);
        Short vat = article.getVatRate();
        BigDecimal margin = LowMarginService.marginOf(regular, vat, purchase);
        BigDecimal requiredMargin = LowMarginService.requiredMargin(article.getNomenclatureCode(), required);
        BigDecimal targetPurchase = null;
        BigDecimal targetSale = null;
        if (requiredMargin != null && requiredMargin.compareTo(HUNDRED) < 0) {
            targetPurchase = LowMarginService.targetPurchase(regular, vat, requiredMargin);
            targetSale = Margin.salePrice(purchase, BigDecimal.valueOf(vat), requiredMargin, 2, RoundingMode.CEILING);
        }
        BigDecimal promo = article.getPromoPrice();
        boolean promoNow = promo != null && promo.signum() > 0 && promo.compareTo(regular) < 0;
        return new Item(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                article.getNomenclatureCode(), article.getRegionCode(), article.getSupplierCode(),
                article.getSupplierName(), BigDecimal.valueOf(vat), purchase,
                fromDelivery ? PurchaseSource.DELIVERY : PurchaseSource.CATALOG,
                fromDelivery ? article.getLastPurchaseDate() : null, regular, margin, requiredMargin, targetPurchase,
                targetSale, promoNow ? promo : null, promoNow ? article.getPromoFrom() : null,
                promoNow ? article.getPromoTo() : null,
                promoNow && article.getPromoSource() != null ? PromoSource.valueOf(article.getPromoSource()) : null,
                promoNow ? LowMarginService.marginOf(promo, vat, purchase) : null,
                article.getSoldQuantity(), article.getCadencier(),
                article.getCadencierPrice(), null);
    }
}
