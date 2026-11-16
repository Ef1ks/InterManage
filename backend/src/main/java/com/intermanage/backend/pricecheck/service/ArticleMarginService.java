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
import com.intermanage.backend.common.component.ReportPeriod;
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
 * aktywność i stan z kartoteki nic nie mówią), z aktualnych cen, a nie z przyjęć: artykuły z marżą niższą od wymaganej na
 * nomenklaturze co najmniej o wybrany próg (np. 5 punktów: wymagane 30%, jest 25% albo mniej) i aktualne promocje z marżą
 * na cenie promocyjnej. Cena zakupu z ostatniego
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
    /** Domyślnie raport pokazuje artykuły z marżą niższą od wymaganej co najmniej o tyle punktów procentowych. */
    static final int DEFAULT_MIN_GAP = 5;
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
    private final ReportExclusionService exclusions;

    public ArticleMarginService(ArticleMarginRepository articles, NomenclatureMarginService margins,
            ExcludedSuppliers excludedSuppliers,
            ReportExcludedSupplierService reportSuppliers, SaleRepository sales,
            ArticleMarginVerificationRepository verifications, PriceCheckMapper mapper, StoreRepository stores,
            AppUserRepository users, ReportExclusionService exclusions) {
        this.exclusions = exclusions;
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

    /**
     * Artykuły z marżą niższą od wymaganej na nomenklaturze co najmniej o {@code minGap} punktów procentowych (bez niego
     * {@value #DEFAULT_MIN_GAP}), od największego odchylenia. Artykuł bez marży wymaganej nie jest oceniany.
     */
    @Transactional(readOnly = true)
    public ArticleMarginReport lowMargins(UUID storeId, BigDecimal minGap, Period delivered, Period sold) {
        if (minGap != null && (minGap.signum() < 0 || minGap.compareTo(HUNDRED) > 0)) {
            throw ApiException.badRequest("Odchylenie musi mieć od 0 do 100 punktów procentowych.");
        }
        BigDecimal gap = minGap != null ? minGap : BigDecimal.valueOf(DEFAULT_MIN_GAP);
        Context context = context(storeId, delivered, sold);
        List<String> excludedCodes = exclusions.codes(storeId, ReviewedReport.ARTICLE_MARGIN);
        int nomenclatureSkipped = 0;
        List<ReportSupplier> skippedSuppliers = reportSuppliers.active(storeId, ReviewedReport.ARTICLE_MARGIN);
        Set<String> skippedCodes = new HashSet<>();
        skippedSuppliers.forEach(supplier -> skippedCodes.add(supplier.code()));
        int supplierSkipped = 0;
        Map<String, ArticleMarginVerification> verified = new HashMap<>();
        verifications.findActive(storeId).forEach(verification -> verified.put(verification.getItm8(), verification));
        List<Item> items = new ArrayList<>();
        for (ArticleMargin article : context.rows()) {
            if (context.excluded().contains(article.getSupplierCode())) {
                continue;
            }
            Item item = item(article, context.required());
            if (item != null && item.requiredMargin() != null
                    && item.marginPercent().compareTo(item.requiredMargin().subtract(gap)) <= 0) {
                if (ReportExclusionService.matches(item.nomenclatureCode(), excludedCodes)) {
                    nomenclatureSkipped++;
                    continue;
                }
                if (item.supplierCode() != null && skippedCodes.contains(item.supplierCode())) {
                    supplierSkipped++;
                    continue;
                }
                ArticleMarginVerification verification = verified.get(item.itm8());
                items.add(verification != null && verification.covers(item.salePrice(), item.purchasePrice(),
                        item.lastPurchaseDate())
                        ? item.withVerification(mapper.toResponse(verification)) : item);
            }
        }
        items.sort(Comparator.comparing((Item item) -> item.marginPercent().subtract(item.requiredMargin()))
                .thenComparing(Item::itm8));
        return new ArticleMarginReport(context.today(), gap, !context.required().isEmpty(), 0,
                context.withoutMargin(), excludedCodes, nomenclatureSkipped, skippedSuppliers, supplierSkipped,
                reportSuppliers.options(storeId), items);
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
        Context context = context(storeId, Period.NONE, Period.NONE);
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
                context.withoutMargin(), List.of(), 0, List.of(), 0, List.of(), items);
    }

    private record Context(LocalDate today, Map<String, BigDecimal> required,
            List<ReportSupplier> withoutMargin, Set<String> excluded, List<ArticleMargin> rows) {
    }

    /**
     * Okres filtra raportu, oba końce włącznie; pusty koniec: bez przyjęcia od początku roku wstecz
     * ({@value #PURCHASE_LOOKBACK_DAYS} dni), bez sprzedaży od {@value #SOLD_MONTHS} miesięcy, do dziś.
     */
    public record Period(LocalDate from, LocalDate to) {
        public static final Period NONE = new Period(null, null);

        boolean active() {
            return from != null || to != null;
        }
    }

    private Context context(UUID storeId, Period delivered, Period sold) {
        sales.tuneForReport();
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        LocalDate deliveredTo = delivered.to() != null ? delivered.to() : today;
        LocalDate deliveredFrom = delivered.from() != null ? delivered.from()
                : delivered.active() ? deliveredTo.minusDays(PURCHASE_LOOKBACK_DAYS) : today.minusMonths(SOLD_MONTHS);
        ReportPeriod.check(deliveredFrom, deliveredTo, ReportPeriod.MAX_DAYS, "Okres przyjęcia");
        LocalDate soldTo = sold.to() != null ? sold.to() : today;
        LocalDate soldFrom = sold.from() != null ? sold.from()
                : sold.active() ? soldTo.withDayOfMonth(1).minusMonths(SOLD_MONTHS - 1L)
                        : today.withDayOfMonth(1).minusMonths(SOLD_MONTHS - 1L);
        ReportPeriod.check(soldFrom, soldTo, ReportPeriod.MAX_DAYS, "Okres sprzedaży");
        if (!soldTo.isBefore(today)) {
            // Sprzedaży z przyszłości nie ma, a bieżący miesiąc w sumach miesięcznych jest tym samym co dni.
            soldTo = soldTo.withDayOfMonth(soldTo.lengthOfMonth());
        }
        // Pełne miesiące okresu z sum miesięcznych, dni na brzegach ze sprzedaży dziennej.
        LocalDate monthFrom = soldFrom.getDayOfMonth() == 1 ? soldFrom : soldFrom.withDayOfMonth(1).plusMonths(1);
        LocalDate monthTo = soldTo.plusDays(1).getDayOfMonth() == 1 ? soldTo.plusDays(1) : soldTo.withDayOfMonth(1);
        if (monthTo.isBefore(monthFrom)) {
            monthTo = monthFrom;
        }
        LocalDate purchaseSince = today.minusDays(PURCHASE_LOOKBACK_DAYS);
        if (deliveredFrom.isBefore(purchaseSince)) {
            purchaseSince = deliveredFrom;
        }
        List<ReportSupplier> withoutMargin = reportSuppliers.active(storeId, ReviewedReport.LOW_MARGIN);
        Set<String> excluded = new HashSet<>(excludedSuppliers.codes(storeId));
        withoutMargin.forEach(supplier -> excluded.add(supplier.code()));
        List<ArticleMargin> rows = articles.findArticleMargins(storeId, purchaseSince.atStartOfDay(),
                deliveredFrom.atStartOfDay(), deliveredTo.plusDays(1).atStartOfDay(), delivered.active(),
                soldFrom, soldTo, monthFrom, monthTo, sold.active(), today, today, today);
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
        // Promocja z listy bywa droższa od ceny zwykłej (np. sezonowa cena przed świętami z tańszym zakupem w
        // promocji), a i tak jest promocją, więc raport ją pokazuje; aktualne promocje odsiewają je wcześniej.
        BigDecimal promo = article.getPromoPrice();
        boolean promoNow = promo != null && promo.signum() > 0;
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
                article.getCadencierPrice(), article.getPeriodDeliveryDate(), article.getSoldSinceDelivery(), null);
    }
}
