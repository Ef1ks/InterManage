package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.DeliveredArticle;
import com.intermanage.backend.document.repository.DocumentLineRepository.LastDelivery;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.pricecheck.entity.PriceCheckCategory;
import com.intermanage.backend.pricecheck.repository.PriceCheckCategoryRepository;
import com.intermanage.backend.pricecheck.service.PriceCheckService;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.component.SlowMovingPdf;
import com.intermanage.backend.sales.dto.SlowMovingReport;
import com.intermanage.backend.sales.dto.SlowMovingReport.CategoryCount;
import com.intermanage.backend.sales.dto.SlowMovingReport.Delivery;
import com.intermanage.backend.sales.dto.SlowMovingReport.DeliveryItem;
import com.intermanage.backend.sales.dto.SlowMovingReport.Item;
import com.intermanage.backend.sales.dto.SlowMovingReport.SupplierCount;
import com.intermanage.backend.sales.dto.SlowMovingRequest;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SaleRepository.SlowMovingArticle;
import com.intermanage.backend.sales.repository.SlowMovingExcludedArticleRepository;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Niesprzedające się artykuły: na stanie, z rotacją tygodniową (sprzedana ilość × 7 / dni okresu) nie wyższą
 * niż próg. Bez rejonów, które nie są towarem. Stany i ceny zakupu są z ostatniego raportu kartoteki, więc wartość
 * stanu to zamrożony w towarze zakup. Z wybranym okresem dostaw raport pokazuje też przyjęcia z takimi artykułami,
 * żeby było widać, co dalej zamawiamy mimo braku sprzedaży. Artykuły z listy pomijanych sklepu nie wchodzą do raportu,
 * tak samo nowy towar: pierwsza dostawa musi być co najmniej miesiąc przed dniem sprawdzania, bo inaczej artykuł nie
 * miał kiedy się sprzedać.
 */
@Service
@Transactional(readOnly = true)
public class SlowMovingService {

    public static final BigDecimal DEFAULT_MAX_ROTATION = new BigDecimal("0.25");
    public static final String OTHER_CATEGORY = "OTHER";
    private static final String BASE_ORIGIN = "BASE";

    /** Mięso i garmaż: surowce schodzą na produkcję, nie przez kasę, więc wyglądałyby na niesprzedający się towar. */
    private static final List<String> RAW_MATERIAL_REGIONS = List.of("24", "36");
    private static final Set<Integer> DELIVERY_DAYS = Set.of(1, 7, 30);
    private static final LocalDateTime ALL_HISTORY = LocalDate.of(2000, 1, 1).atStartOfDay();
    private static final int NEW_ARTICLE_MONTHS = 1;
    private static final int REGION_CODE_LENGTH = 2;
    private static final int DAYS_IN_WEEK = 7;
    private static final int ROTATION_SCALE = 3;
    private static final int MONEY_SCALE = 2;

    private final SaleRepository sales;
    private final DocumentLineRepository documentLines;
    private final PriceCheckCategoryRepository categories;
    private final ProductImportRepository productImports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SalesReportService salesReports;
    private final SlowMovingPdf reportPdf;
    private final SlowMovingExcludedArticleRepository excluded;

    public SlowMovingService(SaleRepository sales, DocumentLineRepository documentLines,
            PriceCheckCategoryRepository categories, ProductImportRepository productImports, StoreRepository stores,
            AppUserRepository users, SalesReportService salesReports, SlowMovingPdf reportPdf,
            SlowMovingExcludedArticleRepository excluded) {
        this.sales = sales;
        this.documentLines = documentLines;
        this.categories = categories;
        this.productImports = productImports;
        this.stores = stores;
        this.users = users;
        this.salesReports = salesReports;
        this.reportPdf = reportPdf;
        this.excluded = excluded;
    }

    public SlowMovingReport report(UUID storeId, SlowMovingRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        ReportPeriod.check(request.from(), request.to());
        BigDecimal maxRotation = request.maxRotation() != null ? request.maxRotation() : DEFAULT_MAX_ROTATION;
        if (maxRotation.signum() < 0) {
            throw ApiException.badRequest("Rotacja nie może być ujemna.");
        }
        if (request.deliveryDays() != null && !DELIVERY_DAYS.contains(request.deliveryDays())) {
            throw ApiException.badRequest("Dostawy można pokazać z ostatniego dnia, 7 albo 30 dni.");
        }

        sales.tuneForReport();
        List<String> nomenclatures = request.nomenclatures() != null ? request.nomenclatures() : List.of();
        long days = request.from().datesUntil(request.to()).count() + 1;
        Map<String, LastDelivery> lastDeliveries = documentLines.findLastDeliveries(storeId, ALL_HISTORY).stream()
                .collect(Collectors.toMap(LastDelivery::getItm8, Function.identity()));
        Set<String> excludedArticles = new HashSet<>(excluded.findActiveItm8s(storeId));
        excludedArticles.addAll(documentLines.findFirstDeliveredSince(storeId, ALL_HISTORY,
                LocalDate.now(StoreTime.ZONE).minusMonths(NEW_ARTICLE_MONTHS).plusDays(1).atStartOfDay()));
        List<Item> articles = sales.findSlowMoving(storeId, request.from(), request.to(),
                maxRotation.multiply(BigDecimal.valueOf(days)), nomenclatures.toArray(String[]::new)).stream()
                .filter(article -> !RegionGroups.isNotGoods(article.getSubfamilyCode()))
                .filter(article -> !excludedArticles.contains(article.getItm8()))
                .map(article -> item(article, days, lastDeliveries.get(article.getItm8())))
                .filter(article -> !hidesRawMaterials(request, nomenclatures) || !isRawMaterial(article))
                .filter(filters(request))
                .toList();

        List<DeliveredArticle> delivered = request.deliveryDays() == null ? List.of()
                : documentLines.findDeliveredArticles(storeId, LocalDate.now(StoreTime.ZONE)
                        .minusDays(request.deliveryDays()).atStartOfDay());
        if (request.deliveryDays() != null) {
            Set<String> deliveredArticles = delivered.stream().map(DeliveredArticle::getItm8)
                    .collect(Collectors.toSet());
            articles = articles.stream().filter(article -> deliveredArticles.contains(article.itm8())).toList();
        }

        List<SupplierCount> suppliers = suppliers(articles);
        if (present(request.supplier())) {
            articles = articles.stream().filter(article -> request.supplier().equals(article.supplierCode())).toList();
        }

        List<PriceCheckCategory> storeCategories = categories.findActive(storeId);
        List<CategoryCount> categoryCounts = new ArrayList<>();
        for (PriceCheckCategory category : storeCategories) {
            int count = (int) articles.stream().filter(article -> inCategory(article, category)).count();
            categoryCounts.add(new CategoryCount(category.getId(), count));
        }
        Predicate<Item> inOther = article -> storeCategories.stream().noneMatch(category -> inCategory(article, category));
        int allCount = articles.size();
        int otherCount = (int) articles.stream().filter(inOther).count();
        List<Item> items = articles.stream()
                .filter(categoryFilter(request.category(), storeCategories, inOther))
                .sorted(sort(request))
                .toList();

        return new SlowMovingReport(store.getNumber(), store.getName(),
                productImports.findNewestReportGeneratedAt(storeId).orElse(null), request.from(), request.to(),
                maxRotation, items.size(), (int) items.stream().filter(item -> item.soldQuantity().signum() <= 0).count(),
                items.stream().map(Item::stockValue).filter(value -> value != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                allCount, otherCount, categoryCounts, suppliers, selection(storeId, nomenclatures), items,
                deliveries(delivered, items));
    }

    public ExportFile pdf(UUID storeId, SlowMovingRequest request, UUID userId) {
        SlowMovingReport report = report(storeId, request);
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        return new ExportFile("niesprzedajace-sie-" + report.storeNumber() + ".pdf", "application/pdf",
                reportPdf.pdf(report, filterNotes(storeId, request, report), printedBy));
    }

    private static Item item(SlowMovingArticle article, long days, LastDelivery delivery) {
        BigDecimal rotation = article.getSoldQuantity().multiply(BigDecimal.valueOf(DAYS_IN_WEEK))
                .divide(BigDecimal.valueOf(days), ROTATION_SCALE, RoundingMode.HALF_UP);
        BigDecimal value = article.getPurchasePrice() == null ? null
                : article.getStock().multiply(article.getPurchasePrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new Item(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                article.getSubfamilyCode(), article.getCadencier(), article.getStock(), value,
                article.getSoldQuantity(), rotation,
                delivery != null ? delivery.getDocumentDate().toLocalDate() : null,
                delivery != null ? delivery.getSupplierCode() : null,
                delivery != null ? delivery.getSupplierName() : null);
    }

    /** Wybrana nomenklatura jest świadomym wyborem, więc wtedy surowców nie ukrywamy. */
    private static boolean hidesRawMaterials(SlowMovingRequest request, List<String> nomenclatures) {
        return nomenclatures.isEmpty() && !Boolean.TRUE.equals(request.withRawMaterials());
    }

    private static boolean isRawMaterial(Item article) {
        String code = article.subfamilyCode();
        return code != null && RAW_MATERIAL_REGIONS.stream().anyMatch(code::startsWith);
    }

    private static Predicate<Item> filters(SlowMovingRequest request) {
        List<String> words = present(request.query())
                ? List.of(request.query().strip().toLowerCase(Locale.ROOT).split("\\s+"))
                : List.of();
        return item -> (request.cadencier() == null || item.cadencier() == "WITH".equals(request.cadencier()))
                && (request.minStockValue() == null || (item.stockValue() != null
                        && item.stockValue().compareTo(request.minStockValue()) >= 0))
                && matchesOrigin(item, request.origin())
                && !(Boolean.TRUE.equals(request.withoutDeliveries()) && item.lastDeliveryDate() != null
                        && !item.lastDeliveryDate().isBefore(request.from()))
                && words.stream().allMatch(word -> matches(item, word));
    }

    /** Skąd towar przyszedł mówi ostatnia dostawa; artykuł bez dostawy nie jest ani z bazy, ani spoza niej. */
    private static boolean matchesOrigin(Item item, String origin) {
        if (origin == null) {
            return true;
        }
        return item.supplierCode() != null
                && PriceCheckService.BASE_SUPPLIER_CODES.contains(item.supplierCode()) == BASE_ORIGIN.equals(origin);
    }

    private static boolean matches(Item item, String word) {
        return contains(item.name(), word) || contains(item.brand(), word) || contains(item.ean(), word)
                || contains(item.itm8(), word);
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(word);
    }

    private static List<SupplierCount> suppliers(List<Item> articles) {
        Map<String, String> names = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Item article : articles) {
            if (article.supplierCode() != null) {
                names.put(article.supplierCode(), article.supplierName());
                counts.merge(article.supplierCode(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new SupplierCount(entry.getKey(), names.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(SupplierCount::name, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static boolean inCategory(Item article, PriceCheckCategory category) {
        String code = article.subfamilyCode();
        return code != null && code.length() >= REGION_CODE_LENGTH
                && category.getRegionCodes().contains(code.substring(0, REGION_CODE_LENGTH));
    }

    /** Nieznana kategoria (np. usunięta, gdy adres został w zakładce) nie zawęża raportu. */
    private static Predicate<Item> categoryFilter(String key, List<PriceCheckCategory> categories,
            Predicate<Item> inOther) {
        if (OTHER_CATEGORY.equals(key)) {
            return inOther;
        }
        return categories.stream().filter(category -> category.getId().toString().equals(key)).findFirst()
                .<Predicate<Item>>map(category -> article -> inCategory(article, category))
                .orElse(article -> true);
    }

    /** Domyślnie od największej wartości stanu; puste wartości zawsze na końcu. */
    private static Comparator<Item> sort(SlowMovingRequest request) {
        boolean descending = request.sort() == null || "desc".equals(request.direction());
        Comparator<Item> chosen = switch (request.sort() == null ? "value" : request.sort()) {
            case "value" -> by(Item::stockValue, descending);
            case "name" -> by(Item::name, descending);
            case "itm8" -> by(Item::itm8, descending);
            case "supplier" -> by(Item::supplierName, descending);
            case "delivery" -> by(Item::lastDeliveryDate, descending);
            case "stock" -> by(Item::stock, descending);
            case "sold" -> by(Item::soldQuantity, descending);
            case "rotation" -> by(Item::weeklyRotation, descending);
            default -> throw ApiException.badRequest("Nieznane sortowanie: " + request.sort());
        };
        return chosen.thenComparing(Item::itm8);
    }

    private static <T extends Comparable<? super T>> Comparator<Item> by(Function<Item, T> key, boolean descending) {
        Comparator<T> order = descending ? Comparator.<T>reverseOrder() : Comparator.<T>naturalOrder();
        return Comparator.comparing(key, Comparator.nullsLast(order));
    }

    private List<NomenclatureOption> selection(UUID storeId, List<String> nomenclatures) {
        if (nomenclatures.isEmpty()) {
            return List.of();
        }
        Set<String> codes = new HashSet<>(nomenclatures);
        return salesReports.nomenclatures(storeId).stream().filter(option -> codes.contains(option.code())).toList();
    }

    /** Przyjęcia z artykułami, które zostały w raporcie po wszystkich filtrach. */
    private static List<Delivery> deliveries(List<DeliveredArticle> delivered, List<Item> items) {
        Set<String> reported = items.stream().map(Item::itm8).collect(Collectors.toSet());
        Map<UUID, List<DeliveredArticle>> documents = new LinkedHashMap<>();
        for (DeliveredArticle line : delivered) {
            if (reported.contains(line.getItm8())) {
                documents.computeIfAbsent(line.getDocumentId(), id -> new ArrayList<>()).add(line);
            }
        }
        return documents.values().stream().map(lines -> {
            DeliveredArticle first = lines.getFirst();
            return new Delivery(first.getDocumentId(), first.getDocumentNumber(), first.getDocumentDate(),
                    first.getSupplierCode(), first.getSupplierName(),
                    lines.stream().map(DeliveredArticle::getPurchaseValue).reduce(BigDecimal.ZERO, BigDecimal::add),
                    lines.stream().map(line -> new DeliveryItem(line.getItm8(), line.getQuantity(),
                            line.getPurchaseValue())).toList());
        }).toList();
    }

    /** Filtry opisane na wydruku, żeby kartka mówiła, co na niej jest. */
    private List<String> filterNotes(UUID storeId, SlowMovingRequest request, SlowMovingReport report) {
        List<String> notes = new ArrayList<>();
        int excludedCount = excluded.findActiveItm8s(storeId).size();
        if (excludedCount > 0) {
            notes.add("Bez artykułów pomijanych (" + excludedCount + ")");
        }
        notes.add("Bez artykułów z pierwszą dostawą w ostatnim miesiącu");
        if (hidesRawMaterials(request, report.selection().stream().map(NomenclatureOption::code).toList())) {
            notes.add("Bez rejonów surowców " + String.join(" i ", RAW_MATERIAL_REGIONS));
        }
        if (!report.selection().isEmpty()) {
            notes.add("Nomenklatura: " + report.selection().stream()
                    .map(option -> option.code() + " " + option.name()).collect(Collectors.joining(", ")));
        }
        if (OTHER_CATEGORY.equals(request.category())) {
            notes.add("Kategoria: Pozostałe");
        } else {
            categories.findActive(storeId).stream()
                    .filter(category -> category.getId().toString().equals(request.category())).findFirst()
                    .ifPresent(category -> notes.add("Kategoria: " + category.getName()));
        }
        if (present(request.supplier())) {
            report.suppliers().stream().filter(supplier -> supplier.code().equals(request.supplier())).findFirst()
                    .ifPresent(supplier -> notes.add("Ostatni dostawca: " + supplier.name()));
        }
        if (request.cadencier() != null) {
            notes.add("WITH".equals(request.cadencier()) ? "Tylko z cadencier" : "Tylko spoza cadencier");
        }
        if (request.origin() != null) {
            notes.add(BASE_ORIGIN.equals(request.origin()) ? "Ostatnia dostawa z bazy" : "Ostatnia dostawa spoza bazy");
        }
        if (request.minStockValue() != null) {
            notes.add("Wartość stanu od " + request.minStockValue().toPlainString() + " zł");
        }
        if (Boolean.TRUE.equals(request.withoutDeliveries())) {
            notes.add("Bez artykułów z dostawą w okresie");
        }
        if (present(request.query())) {
            notes.add("Szukane: " + request.query().strip());
        }
        return notes;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
