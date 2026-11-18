package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.pricecheck.service.PriceCheckService;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.sales.component.InStockSales;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.dto.MissingAssortmentAssignRequest;
import com.intermanage.backend.sales.dto.MissingAssortmentCategoryRequest;
import com.intermanage.backend.sales.dto.MissingAssortmentCategoryResponse;
import com.intermanage.backend.sales.dto.MissingAssortmentReport;
import com.intermanage.backend.sales.dto.MissingAssortmentReport.Item;
import com.intermanage.backend.sales.dto.MissingAssortmentRequest;
import com.intermanage.backend.sales.entity.MissingAssortmentAssignment;
import com.intermanage.backend.sales.entity.MissingAssortmentCategory;
import com.intermanage.backend.sales.mapper.MissingAssortmentMapper;
import com.intermanage.backend.sales.repository.MissingAssortmentAssignmentRepository;
import com.intermanage.backend.sales.repository.MissingAssortmentCategoryRepository;
import com.intermanage.backend.sales.repository.MissingAssortmentRepository;
import com.intermanage.backend.sales.repository.MissingAssortmentRepository.BoughtArticle;
import com.intermanage.backend.sales.repository.MissingAssortmentRepository.DaySale;
import com.intermanage.backend.sales.repository.MissingAssortmentRepository.DeliveryDay;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.StockGapRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.entity.StorePartner;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Brakujący asortyment: co sklep kupował od dostawców spoza bazy, a czego dziś nie ma i co się nie sprzedaje. Kierownik
 * widzi, czego brakuje od którego dostawcy, i odkłada artykuły do kategorii (np. „wycofane z produkcji”), żeby lista
 * „Do sprawdzenia” zawierała tylko to, czym trzeba się zająć. Kategorie są wspólne ze sklepami zaprzyjaźnionymi, a
 * przypisania każdy sklep robi u siebie.
 */
@Service
public class MissingAssortmentService {

    public static final int DEFAULT_MONTHS = 3;
    public static final int DEFAULT_SILENT_DAYS = 7;
    /** Z ilu dni wstecz odtwarzamy okresy na stanie. */
    private static final int HISTORY_DAYS = 365;
    /** Mięso, garmaż i wyroby własne: stan nie mówi tu, ile jest towaru, bo schodzi na produkcję. */
    private static final List<String> PRODUCTION_REGIONS = List.of("24", "36", "46");
    private static final UUID NONE = new UUID(0, 0);

    private final MissingAssortmentRepository report;
    private final MissingAssortmentCategoryRepository categories;
    private final MissingAssortmentAssignmentRepository assignments;
    private final StockGapRepository stockGaps;
    private final SaleRepository sales;
    private final ProductImportRepository productImports;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;
    private final MissingAssortmentMapper mapper;

    public MissingAssortmentService(MissingAssortmentRepository report, MissingAssortmentCategoryRepository categories,
            MissingAssortmentAssignmentRepository assignments, StockGapRepository stockGaps, SaleRepository sales,
            ProductImportRepository productImports, StoreRepository stores, StorePartnerRepository partners,
            AppUserRepository users, MissingAssortmentMapper mapper) {
        this.report = report;
        this.categories = categories;
        this.assignments = assignments;
        this.stockGaps = stockGaps;
        this.sales = sales;
        this.productImports = productImports;
        this.stores = stores;
        this.partners = partners;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MissingAssortmentReport report(UUID storeId, MissingAssortmentRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        int months = request.months() != null ? request.months() : DEFAULT_MONTHS;
        int silentDays = request.silentDays() != null ? request.silentDays() : DEFAULT_SILENT_DAYS;
        if (months < 1 || months > 12) {
            throw ApiException.badRequest("Okres zakupów to od 1 do 12 miesięcy.");
        }
        if (silentDays < 0 || silentDays > 365) {
            throw ApiException.badRequest("Liczba dni bez sprzedaży to od 0 do 365.");
        }
        List<String> nomenclatures = request.nomenclatures() != null ? request.nomenclatures() : List.of();
        LocalDate since = LocalDate.now(StoreTime.ZONE).minusMonths(months);
        LocalDate lastSaleDate = stockGaps.findLastSaleDate(storeId).orElse(null);

        sales.tuneForReport();
        List<BoughtArticle> bought = report.findBoughtWithoutStock(storeId, since.atStartOfDay(),
                        PriceCheckService.BASE_SUPPLIER_CODES.toArray(String[]::new),
                        nomenclatures.toArray(String[]::new))
                .stream()
                .filter(article -> isGoods(article.getSubfamilyCode()))
                .filter(article -> isSilent(article.getLastSale(), lastSaleDate, silentDays))
                .toList();

        Map<String, InStockSales.Result> inStock = inStock(storeId, bought, lastSaleDate);
        List<MissingAssortmentCategoryResponse> visible = visibleCategories(storeId);
        Set<UUID> visibleIds = visible.stream().map(MissingAssortmentCategoryResponse::id).collect(Collectors.toSet());
        Map<String, UUID> assigned = new HashMap<>();
        for (MissingAssortmentAssignment assignment : assignments.findActive(storeId)) {
            if (visibleIds.contains(assignment.getCategory().getId())) {
                assigned.put(assignment.getItm8(), assignment.getCategory().getId());
            }
        }

        List<Item> items = new ArrayList<>();
        for (BoughtArticle article : bought) {
            items.add(item(article, inStock.get(article.getItm8()), lastSaleDate, assigned.get(article.getItm8())));
        }
        items.sort(Comparator.comparing(Item::weeklyValue, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Item::name, Comparator.nullsLast(Comparator.naturalOrder())));

        return new MissingAssortmentReport(store.getNumber(), store.getName(),
                productImports.findNewestReportGeneratedAt(storeId).orElse(null), lastSaleDate, since, months,
                silentDays, PriceCheckService.BASE_SUPPLIER_CODES, visible, items);
    }

    private static boolean isGoods(String subfamilyCode) {
        return !RegionGroups.isNotGoods(subfamilyCode)
                && (subfamilyCode == null || PRODUCTION_REGIONS.stream().noneMatch(subfamilyCode::startsWith));
    }

    private static boolean isSilent(LocalDate lastSale, LocalDate lastSaleDate, int silentDays) {
        return lastSale == null || lastSaleDate == null
                || ChronoUnit.DAYS.between(lastSale, lastSaleDate) >= silentDays;
    }

    private Map<String, InStockSales.Result> inStock(UUID storeId, List<BoughtArticle> bought,
            LocalDate lastSaleDate) {
        if (bought.isEmpty() || lastSaleDate == null) {
            return Map.of();
        }
        String[] itm8s = bought.stream().map(BoughtArticle::getItm8).toArray(String[]::new);
        LocalDate from = lastSaleDate.minusDays(HISTORY_DAYS);
        Map<String, List<LocalDate>> deliveries = report.findDeliveryDays(storeId, from.atStartOfDay(), itm8s)
                .stream()
                .collect(Collectors.groupingBy(DeliveryDay::getItm8,
                        Collectors.mapping(DeliveryDay::getDay, Collectors.toList())));
        Map<String, NavigableMap<LocalDate, BigDecimal>> daily = new HashMap<>();
        for (DaySale sale : report.findDailySales(storeId, from, lastSaleDate, itm8s)) {
            daily.computeIfAbsent(sale.getItm8(), key -> new TreeMap<>()).merge(sale.getDay(), sale.getQuantity(),
                    BigDecimal::add);
        }
        Map<String, InStockSales.Result> result = new HashMap<>();
        deliveries.forEach((itm8, days) -> {
            NavigableMap<LocalDate, BigDecimal> articleSales = daily.get(itm8);
            if (articleSales != null) {
                InStockSales.Result computed = InStockSales.of(days.stream().sorted().toList(), articleSales,
                        lastSaleDate);
                if (computed != null) {
                    result.put(itm8, computed);
                }
            }
        });
        return result;
    }

    private static Item item(BoughtArticle article, InStockSales.Result inStock, LocalDate lastSaleDate,
            UUID categoryId) {
        BigDecimal lastPrice = article.getLastQuantity() == null || article.getLastQuantity().signum() == 0
                || article.getLastValue() == null ? null
                        : article.getLastValue().divide(article.getLastQuantity(), 2, RoundingMode.HALF_UP);
        BigDecimal weekly = inStock != null ? inStock.weekly() : null;
        BigDecimal weeklyValue = weekly != null && article.getSalePrice() != null
                ? weekly.multiply(article.getSalePrice()).setScale(2, RoundingMode.HALF_UP)
                : null;
        Long silent = article.getLastSale() != null && lastSaleDate != null
                ? ChronoUnit.DAYS.between(article.getLastSale(), lastSaleDate)
                : null;
        return new Item(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                article.getSubfamilyCode(), article.getCadencier(),
                article.getStock() != null ? article.getStock() : BigDecimal.ZERO, article.getSalePrice(),
                article.getSupplierCode(), article.getSupplierName(), article.getPurchases(),
                article.getLastPurchase().toLocalDate(), article.getLastQuantity(), lastPrice, article.getLastSale(),
                silent, inStock != null ? inStock.sold() : null, inStock != null ? inStock.days() : null, weekly,
                weeklyValue, categoryId);
    }

    // Kategorie

    @Transactional(readOnly = true)
    public List<MissingAssortmentCategoryResponse> categories(UUID storeId) {
        return visibleCategories(storeId);
    }

    @Transactional
    public List<MissingAssortmentCategoryResponse> create(UUID storeId, MissingAssortmentCategoryRequest request,
            UUID userId) {
        categories.save(new MissingAssortmentCategory(stores.getReferenceById(storeId), name(storeId, request, NONE),
                user(userId)));
        return visibleCategories(storeId);
    }

    @Transactional
    public List<MissingAssortmentCategoryResponse> rename(UUID storeId, UUID id,
            MissingAssortmentCategoryRequest request) {
        MissingAssortmentCategory category = owned(storeId, id);
        if (category.getDeletedAt() != null) {
            throw ApiException.notFound("Nie znaleziono kategorii");
        }
        category.rename(name(storeId, request, id));
        return visibleCategories(storeId);
    }

    /** Artykuły z usuniętej kategorii wracają do „Do sprawdzenia”, a po przywróceniu znów są w niej. */
    @Transactional
    public List<MissingAssortmentCategoryResponse> delete(UUID storeId, UUID id, UUID userId) {
        MissingAssortmentCategory category = owned(storeId, id);
        if (category.getDeletedAt() == null) {
            category.delete(user(userId));
        }
        return visibleCategories(storeId);
    }

    @Transactional
    public List<MissingAssortmentCategoryResponse> restore(UUID storeId, UUID id) {
        MissingAssortmentCategory category = owned(storeId, id);
        if (category.getDeletedAt() != null) {
            if (categories.existsActiveName(storeId, category.getName(), id)) {
                throw ApiException.conflict("Jest już kategoria o nazwie „" + category.getName() + "”");
            }
            category.restore();
        }
        return visibleCategories(storeId);
    }

    /**
     * Każdy artykuł trafia do swojej kategorii: poprzednie przypisanie dostaje znacznik, pusta kategoria zostawia
     * artykuł bez kategorii. Artykuł już w tej kategorii zostaje z pierwszym wpisem.
     */
    @Transactional
    public List<MissingAssortmentCategoryResponse> assign(UUID storeId, MissingAssortmentAssignRequest request,
            UUID userId) {
        Map<String, UUID> wanted = new LinkedHashMap<>();
        for (MissingAssortmentAssignRequest.Item item : request.items()) {
            wanted.put(item.itm8().trim(), item.categoryId());
        }
        Map<UUID, MissingAssortmentCategory> usable = categories.findActive(visibleStoreIds(storeId)).stream()
                .collect(Collectors.toMap(MissingAssortmentCategory::getId, Function.identity()));
        for (UUID categoryId : new LinkedHashSet<>(wanted.values())) {
            if (categoryId != null && !usable.containsKey(categoryId)) {
                throw ApiException.badRequest("Nie ma takiej kategorii w tym sklepie ani w zaprzyjaźnionych");
            }
        }
        AppUser user = user(userId);
        Store store = stores.getReferenceById(storeId);
        Map<String, MissingAssortmentAssignment> current = assignments.findActive(storeId, wanted.keySet()).stream()
                .collect(Collectors.toMap(MissingAssortmentAssignment::getItm8, Function.identity()));
        List<MissingAssortmentAssignment> added = new ArrayList<>();
        wanted.forEach((itm8, categoryId) -> {
            MissingAssortmentAssignment existing = current.get(itm8);
            if (existing != null && existing.getCategory().getId().equals(categoryId)) {
                return;
            }
            if (existing != null) {
                existing.remove(user);
            }
            if (categoryId != null) {
                added.add(new MissingAssortmentAssignment(store, itm8, usable.get(categoryId), user));
            }
        });
        // Znaczniki przed nowymi wpisami, bo indeks pozwala na jedno obowiązujące przypisanie artykułu.
        assignments.flush();
        assignments.saveAll(added);
        return visibleCategories(storeId);
    }

    private List<MissingAssortmentCategoryResponse> visibleCategories(UUID storeId) {
        Map<UUID, Long> counts = assignments.findActive(storeId).stream()
                .collect(Collectors.groupingBy(assignment -> assignment.getCategory().getId(), Collectors.counting()));
        return categories.findActive(visibleStoreIds(storeId)).stream()
                .map(category -> mapper.toResponse(category, category.getStore().getId().equals(storeId),
                        counts.getOrDefault(category.getId(), 0L)))
                .sorted(Comparator.comparing(MissingAssortmentCategoryResponse::own).reversed()
                        .thenComparing(category -> category.name().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /** Sklep i jego zaakceptowane sklepy zaprzyjaźnione. */
    private Set<UUID> visibleStoreIds(UUID storeId) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(storeId);
        for (StorePartner partner : partners.findActive(storeId)) {
            if (partner.getAcceptedAt() != null) {
                ids.add(partner.getStore().getId().equals(storeId) ? partner.getPartnerStore().getId()
                        : partner.getStore().getId());
            }
        }
        return ids;
    }

    /** Kategorię zmienia i usuwa tylko sklep, który ją założył. */
    private MissingAssortmentCategory owned(UUID storeId, UUID id) {
        return categories.findOwned(storeId, id).orElseThrow(() -> ApiException
                .notFound("Nie znaleziono kategorii. Kategorię sklepu zaprzyjaźnionego zmienia tylko ten sklep."));
    }

    private String name(UUID storeId, MissingAssortmentCategoryRequest request, UUID exceptId) {
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (categories.existsActiveName(storeId, name, exceptId)) {
            throw ApiException.conflict("Jest już kategoria o nazwie „" + name + "”");
        }
        return name;
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
