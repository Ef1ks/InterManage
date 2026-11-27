package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.intermanage.backend.common.component.Margin;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.component.XlsxWriter;
import com.intermanage.backend.common.component.XlsxWriter.Cell;
import com.intermanage.backend.common.component.XlsxWriter.Column;
import com.intermanage.backend.common.component.XlsxWriter.Row;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.LastDelivery;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.pricecheck.entity.PriceCheckCategory;
import com.intermanage.backend.pricecheck.repository.PriceCheckCategoryRepository;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.sales.component.ArticleListColumn;
import com.intermanage.backend.sales.component.ArticleListPdf;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.dto.ArticleListReport;
import com.intermanage.backend.sales.dto.ArticleListReport.Group;
import com.intermanage.backend.sales.dto.ArticleListReport.Item;
import com.intermanage.backend.sales.dto.ArticleListReport.Option;
import com.intermanage.backend.sales.dto.ArticleListReport.Options;
import com.intermanage.backend.sales.dto.ArticleListRequest;
import com.intermanage.backend.sales.repository.ArticleListRepository;
import com.intermanage.backend.sales.repository.ArticleListRepository.ArticleRow;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Lista artykułów kartoteki z dowolnymi filtrami, wydrukiem i Excelem. Kartoteka sklepu to kilkadziesiąt tysięcy
 * artykułów, więc całość czyta jedno zapytanie, a filtry, grupy i sortowanie liczy pamięć; podgląd dostaje tylko
 * pierwsze artykuły, wydruk i Excel wszystkie. Domyślnie tylko artykuły z najnowszego raportu kartoteki i bez rejonów,
 * które nie są towarem.
 */
@Service
@Transactional(readOnly = true)
public class ArticleListService {

    public static final int PREVIEW_LIMIT = 300;
    /** Więcej wierszy to setki kartek; wtedy wydruk prosi o zawężenie albo same podsumowania. */
    public static final int PDF_LIMIT = 15000;
    public static final String OTHER_CATEGORY = "OTHER";
    private static final List<ArticleListColumn> DEFAULT_COLUMNS = List.of(ArticleListColumn.ITM8,
            ArticleListColumn.EAN, ArticleListColumn.NAME, ArticleListColumn.BRAND, ArticleListColumn.STOCK,
            ArticleListColumn.PURCHASE_PRICE, ArticleListColumn.SALE_PRICE, ArticleListColumn.MARGIN,
            ArticleListColumn.STOCK_VALUE, ArticleListColumn.LAST_SUPPLIER);
    private static final LocalDateTime ALL_HISTORY = LocalDate.of(2000, 1, 1).atStartOfDay();
    private static final int MONEY_SCALE = 2;
    private static final int REGION_CODE_LENGTH = 2;

    private final ArticleListRepository articles;
    private final DocumentLineRepository documentLines;
    private final PriceCheckCategoryRepository categories;
    private final ProductImportRepository productImports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SalesReportService salesReports;
    private final SaleRepository sales;
    private final ArticleListPdf reportPdf;
    private final XlsxWriter xlsx;

    public ArticleListService(ArticleListRepository articles, DocumentLineRepository documentLines,
            PriceCheckCategoryRepository categories, ProductImportRepository productImports, StoreRepository stores,
            AppUserRepository users, SalesReportService salesReports, SaleRepository sales, ArticleListPdf reportPdf,
            XlsxWriter xlsx) {
        this.articles = articles;
        this.documentLines = documentLines;
        this.categories = categories;
        this.productImports = productImports;
        this.stores = stores;
        this.users = users;
        this.salesReports = salesReports;
        this.sales = sales;
        this.reportPdf = reportPdf;
        this.xlsx = xlsx;
    }

    /** Podgląd: liczby i grupy z całości, artykuły do {@link #PREVIEW_LIMIT}. */
    public ArticleListReport report(UUID storeId, ArticleListRequest request) {
        Result result = compute(storeId, request);
        return result.report(result.items.subList(0, Math.min(PREVIEW_LIMIT, result.items.size())));
    }

    public ExportFile pdf(UUID storeId, ArticleListRequest request, UUID userId) {
        Result result = compute(storeId, request);
        boolean withArticles = !Boolean.FALSE.equals(request.articles());
        if (result.items.isEmpty()) {
            throw ApiException.badRequest("Żaden artykuł nie spełnia tych filtrów.");
        }
        if (withArticles && result.items.size() > PDF_LIMIT) {
            throw ApiException.badRequest("Lista ma " + result.items.size() + " artykułów, a wydruk mieści najwyżej "
                    + PDF_LIMIT + ". Zawęź filtry, wydrukuj same podsumowania albo pobierz Excel.");
        }
        if (!withArticles && result.depth == 0) {
            throw ApiException.badRequest("Same podsumowania wymagają grupowania po nomenklaturze.");
        }
        ArticleListReport report = result.report(List.of());
        return new ExportFile("lista-artykulow-" + report.storeNumber() + ".pdf", "application/pdf",
                reportPdf.pdf(report, columns(request), result.items, result.depth, withArticles,
                        filterNotes(storeId, request), printedBy(userId)));
    }

    public ExportFile xlsx(UUID storeId, ArticleListRequest request) {
        Result result = compute(storeId, request);
        List<ArticleListColumn> columns = columns(request);
        ArticleListReport report = result.report(List.of());
        Map<String, Group> groups = new HashMap<>();
        report.groups().forEach(group -> groups.put(ArticleListPdf.keyOf(group.code(), group.level()), group));

        List<Row> rows = new ArrayList<>();
        String[] current = new String[result.depth];
        for (Item item : result.items) {
            for (int level = 1; level <= result.depth; level++) {
                String key = ArticleListPdf.keyOf(ArticleListPdf.codeAt(item, level), level);
                if (!key.equals(current[level - 1])) {
                    current[level - 1] = key;
                    Arrays.fill(current, level, result.depth, null);
                    Group group = groups.get(key);
                    if (group != null) {
                        Object[] cells = new Object[columns.size()];
                        cells[0] = Cell.bold(ArticleListPdf.groupTitle(group) + " · artykułów: " + group.count()
                                + " · stan: " + ArticleListColumn.money(group.stockValue()) + " zł");
                        rows.add(Row.of(cells));
                    }
                }
            }
            rows.add(new Row(columns.stream().map(column -> cellOf(column, item)).toList(), 0));
        }
        List<Row> intro = new ArrayList<>();
        intro.add(Row.of(Cell.bold("Lista artykułów · sklep " + report.storeNumber() + " · artykułów: "
                + report.itemCount())));
        List<String> notes = filterNotes(storeId, request);
        if (!notes.isEmpty()) {
            intro.add(Row.of(String.join(" · ", notes)));
        }
        byte[] content = xlsx.writeRows("Artykuły", intro,
                columns.stream().map(column -> new Column(column.label(), column.xlsxWidth())).toList(), rows);
        return new ExportFile("lista-artykulow-" + report.storeNumber() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    private static Object cellOf(ArticleListColumn column, Item item) {
        Object value = column.value(item);
        if (value instanceof LocalDate) {
            return column.format(item);
        }
        if (column.kind() == ArticleListColumn.Kind.MONEY && value instanceof BigDecimal money) {
            return Cell.money(money);
        }
        return value;
    }

    private record Result(Store store, LocalDateTime generatedAt, List<Item> items, int depth, List<Group> groups,
            Options options) {

        ArticleListReport report(List<Item> shown) {
            return new ArticleListReport(store.getNumber(), store.getName(), generatedAt, items.size(),
                    sum(items, Item::stockValue), sum(items, Item::stockSaleValue), PREVIEW_LIMIT, groups, shown,
                    options);
        }
    }

    private Result compute(UUID storeId, ArticleListRequest request) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        int depth = depth(request.groupBy());
        Comparator<Item> order = sort(request);
        sales.tuneForReport();

        Map<String, LastDelivery> deliveries = documentLines.findLastDeliveries(storeId, ALL_HISTORY).stream()
                .collect(Collectors.toMap(LastDelivery::getItm8, Function.identity(), (first, second) -> first));
        List<Item> base = articles.findArticles(storeId, LocalDate.now(StoreTime.ZONE)).stream()
                .filter(row -> Boolean.TRUE.equals(request.allArticles()) || Boolean.TRUE.equals(row.getInLastReport()))
                .filter(row -> Boolean.TRUE.equals(request.withNotGoods()) || !RegionGroups.isNotGoods(row.getSubfamilyCode()))
                .map(row -> item(row, deliveries.get(row.getItm8())))
                .toList();
        Options options = options(base);

        Predicate<Item> filter = filters(request, categories.findActive(storeId));
        List<Item> items = base.stream().filter(filter)
                .sorted(groupOrder(depth).thenComparing(order))
                .toList();
        return new Result(store, productImports.findNewestReportGeneratedAt(storeId).orElse(null), items, depth,
                groups(storeId, items, depth), options);
    }

    private static Item item(ArticleRow row, LastDelivery delivery) {
        BigDecimal stock = row.getStock();
        BigDecimal margin = null;
        if (row.getSalePrice() != null && row.getSalePrice().signum() > 0 && row.getPurchasePrice() != null
                && row.getVatRate() != null) {
            margin = Margin.percent(row.getSalePrice(), BigDecimal.valueOf(row.getVatRate()), row.getPurchasePrice(),
                    MONEY_SCALE);
        }
        return new Item(row.getItm8(), row.getEan(), row.getName(), row.getBrand(), row.getCatalogSupplier(),
                row.getRegionCode(), row.getRegionName(), row.getFamilyCode(), row.getFamilyName(),
                row.getSubfamilyCode(), row.getSubfamilyName(), stock, row.getRotation(), row.getPurchasePrice(),
                row.getSalePrice(), row.getVatRate() == null ? null : row.getVatRate().intValue(), margin,
                times(stock, row.getPurchasePrice()), times(stock, row.getSalePrice()), row.getUnit(),
                row.getNetContent(), row.getCaseSize(), Boolean.TRUE.equals(row.getActive()), row.getDepositCode(),
                row.getKind(), row.getDlv(), row.getCnCode(), Boolean.TRUE.equals(row.getCadencier()),
                row.getCadencierPrice(), row.getPromoPrice(), row.getPromoFrom(), row.getPromoTo(),
                delivery != null ? delivery.getDocumentDate().toLocalDate() : null,
                delivery != null ? delivery.getSupplierCode() : null,
                delivery != null ? delivery.getSupplierName() : null, Boolean.TRUE.equals(row.getInLastReport()));
    }

    private static BigDecimal times(BigDecimal stock, BigDecimal price) {
        return stock == null || price == null ? null : stock.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static int depth(String groupBy) {
        return switch (groupBy == null ? "NONE" : groupBy) {
            case "NONE" -> 0;
            case "REGION" -> 1;
            case "FAMILY" -> 2;
            case "SUBFAMILY" -> 3;
            default -> throw ApiException.badRequest("Nieznane grupowanie: " + groupBy);
        };
    }

    private static List<ArticleListColumn> columns(ArticleListRequest request) {
        if (request.columns() == null || request.columns().isEmpty()) {
            return DEFAULT_COLUMNS;
        }
        List<ArticleListColumn> columns = new ArrayList<>();
        for (String name : request.columns()) {
            ArticleListColumn column = parseColumn(name);
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static ArticleListColumn parseColumn(String name) {
        try {
            return ArticleListColumn.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Nieznana kolumna: " + name);
        }
    }

    /** Przy grupowaniu najpierw kod grupy (bez nomenklatury na końcu), dopiero potem wybrane sortowanie. */
    private static Comparator<Item> groupOrder(int depth) {
        if (depth == 0) {
            return (a, b) -> 0;
        }
        Function<Item, String> key = item -> {
            String code = ArticleListPdf.codeAt(item, depth);
            return code.isEmpty() ? "~" : code;
        };
        return Comparator.comparing(key);
    }

    private static Comparator<Item> sort(ArticleListRequest request) {
        ArticleListColumn column = request.sort() == null || request.sort().isBlank() ? ArticleListColumn.NAME
                : parseColumn(request.sort());
        boolean descending = "desc".equals(request.direction());
        Comparator<Comparable<Object>> natural = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return Comparator.comparing(column::sortKey, Comparator.nullsLast(natural)).thenComparing(Item::itm8);
    }

    private static Predicate<Item> filters(ArticleListRequest request, List<PriceCheckCategory> storeCategories) {
        List<Predicate<Item>> checks = new ArrayList<>();
        if (present(request.query())) {
            List<String> words = List.of(request.query().strip().toLowerCase(Locale.ROOT).split("\\s+"));
            checks.add(item -> words.stream().allMatch(word -> contains(item.name(), word)
                    || contains(item.brand(), word) || contains(item.ean(), word)
                    || contains(item.itm8().replaceFirst("^0+", ""), word)));
        }
        List<String> nomenclatures = clean(request.nomenclatures());
        if (Boolean.TRUE.equals(request.withoutNomenclature())) {
            checks.add(item -> item.subfamilyCode() == null || item.subfamilyCode().length() < 6);
        } else if (!nomenclatures.isEmpty()) {
            checks.add(item -> item.subfamilyCode() != null
                    && nomenclatures.stream().anyMatch(item.subfamilyCode()::startsWith));
        }
        if (present(request.category())) {
            checks.add(categoryFilter(request.category(), storeCategories));
        }
        Set<String> brands = clean(request.brands()).stream().map(TopSalesService::brandKey).collect(Collectors.toSet());
        if (!brands.isEmpty()) {
            checks.add(item -> brands.contains(TopSalesService.brandKey(item.brand())));
        }
        Set<String> suppliers = new HashSet<>(clean(request.suppliers()));
        if (!suppliers.isEmpty()) {
            checks.add(item -> item.lastSupplierCode() != null && suppliers.contains(item.lastSupplierCode().trim()));
        }
        if (present(request.catalogSupplier())) {
            String text = request.catalogSupplier().strip().toLowerCase(Locale.ROOT);
            checks.add(item -> contains(item.catalogSupplier(), text));
        }
        Set<String> units = rawValues(request.units());
        if (!units.isEmpty()) {
            checks.add(item -> units.contains(valueOrEmpty(item.unit())));
        }
        Set<String> kinds = rawValues(request.kinds());
        if (!kinds.isEmpty()) {
            checks.add(item -> kinds.contains(valueOrEmpty(item.kind())));
        }
        if (request.vatRates() != null && !request.vatRates().isEmpty()) {
            Set<Integer> rates = new HashSet<>(request.vatRates());
            checks.add(item -> item.vatRate() != null && rates.contains(item.vatRate()));
        }
        if (present(request.stock())) {
            checks.add(switch (request.stock()) {
                case "POSITIVE" -> item -> item.stock() != null && item.stock().signum() > 0;
                case "ZERO" -> item -> item.stock() == null || item.stock().signum() == 0;
                case "NEGATIVE" -> item -> item.stock() != null && item.stock().signum() < 0;
                default -> throw ApiException.badRequest("Nieznany filtr stanu: " + request.stock());
            });
        }
        range(checks, Item::stock, request.stockFrom(), request.stockTo());
        range(checks, Item::rotation, request.rotationFrom(), request.rotationTo());
        range(checks, Item::purchasePrice, request.purchaseFrom(), request.purchaseTo());
        range(checks, Item::salePrice, request.saleFrom(), request.saleTo());
        range(checks, Item::marginPercent, request.marginFrom(), request.marginTo());
        range(checks, Item::stockValue, request.valueFrom(), request.valueTo());
        flag(checks, request.cadencier(), Item::cadencier, "cadencier");
        flag(checks, request.promotion(), item -> item.promoPrice() != null, "promocji");
        flag(checks, request.deposit(), item -> item.depositCode() != null && item.depositCode() != 0
                && item.depositCode() != 99, "kaucji");
        flag(checks, request.delivery(), item -> item.lastDeliveryDate() != null, "dostaw");
        if (present(request.active())) {
            boolean active = switch (request.active()) {
                case "ACTIVE" -> true;
                case "INACTIVE" -> false;
                default -> throw ApiException.badRequest("Nieznany filtr aktywności: " + request.active());
            };
            checks.add(item -> item.active() == active);
        }
        if (request.deliveredFrom() != null) {
            checks.add(item -> item.lastDeliveryDate() != null && !item.lastDeliveryDate().isBefore(request.deliveredFrom()));
        }
        if (request.deliveredTo() != null) {
            checks.add(item -> item.lastDeliveryDate() != null && !item.lastDeliveryDate().isAfter(request.deliveredTo()));
        }
        return item -> checks.stream().allMatch(check -> check.test(item));
    }

    private static void range(List<Predicate<Item>> checks, Function<Item, BigDecimal> value, BigDecimal from,
            BigDecimal to) {
        if (from != null) {
            checks.add(item -> value.apply(item) != null && value.apply(item).compareTo(from) >= 0);
        }
        if (to != null) {
            checks.add(item -> value.apply(item) != null && value.apply(item).compareTo(to) <= 0);
        }
    }

    private static void flag(List<Predicate<Item>> checks, String mode, Predicate<Item> has, String what) {
        if (!present(mode)) {
            return;
        }
        checks.add(switch (mode) {
            case "WITH" -> has;
            case "WITHOUT" -> has.negate();
            default -> throw ApiException.badRequest("Nieznany filtr " + what + ": " + mode);
        });
    }

    private static Predicate<Item> categoryFilter(String key, List<PriceCheckCategory> categories) {
        if (OTHER_CATEGORY.equals(key)) {
            return item -> categories.stream().noneMatch(category -> inCategory(item, category));
        }
        return categories.stream().filter(category -> category.getId().toString().equals(key)).findFirst()
                .<Predicate<Item>>map(category -> item -> inCategory(item, category))
                .orElse(item -> true);
    }

    private static boolean inCategory(Item item, PriceCheckCategory category) {
        String code = item.subfamilyCode();
        return code != null && code.length() >= REGION_CODE_LENGTH
                && category.getRegionCodes().contains(code.substring(0, REGION_CODE_LENGTH));
    }

    /** Podsumowania grup w kolejności wydruku: przy każdym poziomie do {@code depth}, bez nomenklatury na końcu. */
    private List<Group> groups(UUID storeId, List<Item> items, int depth) {
        if (depth == 0) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        for (NomenclatureOption option : salesReports.nomenclatures(storeId)) {
            names.put(option.code(), option.name());
        }
        Map<String, List<Item>> byKey = new LinkedHashMap<>();
        for (Item item : items) {
            for (int level = 1; level <= depth; level++) {
                String code = ArticleListPdf.codeAt(item, level);
                if (code.isEmpty() && level > 1) {
                    break;
                }
                byKey.computeIfAbsent(ArticleListPdf.keyOf(code, level), key -> new ArrayList<>()).add(item);
            }
        }
        return byKey.entrySet().stream().map(entry -> {
            int level = Integer.parseInt(entry.getKey().substring(0, 1));
            String code = entry.getKey().substring(2);
            List<Item> grouped = entry.getValue();
            return new Group(code, level, names.get(code), grouped.size(), sum(grouped, Item::stockValue),
                    sum(grouped, Item::stockSaleValue));
        }).toList();
    }

    private static Options options(List<Item> base) {
        Map<String, Integer> brandCounts = new HashMap<>();
        Map<String, Integer> supplierCounts = new HashMap<>();
        Map<String, String> supplierNames = new HashMap<>();
        Map<String, Integer> unitCounts = new HashMap<>();
        Map<String, Integer> kindCounts = new HashMap<>();
        Map<Integer, Integer> vatCounts = new HashMap<>();
        for (Item item : base) {
            String brand = TopSalesService.brandKey(item.brand());
            if (!brand.isEmpty()) {
                brandCounts.merge(brand, 1, Integer::sum);
            }
            if (item.lastSupplierCode() != null) {
                String code = item.lastSupplierCode().trim();
                supplierCounts.merge(code, 1, Integer::sum);
                supplierNames.putIfAbsent(code, item.lastSupplierName());
            }
            unitCounts.merge(valueOrEmpty(item.unit()), 1, Integer::sum);
            kindCounts.merge(valueOrEmpty(item.kind()), 1, Integer::sum);
            if (item.vatRate() != null) {
                vatCounts.merge(item.vatRate(), 1, Integer::sum);
            }
        }
        return new Options(
                byText(brandCounts, Function.identity()),
                supplierCounts.entrySet().stream()
                        .map(entry -> new Option(entry.getKey(), supplierNames.get(entry.getKey()) == null
                                ? entry.getKey() : entry.getKey() + " " + supplierNames.get(entry.getKey()),
                                entry.getValue()))
                        .sorted(Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER)).toList(),
                byText(unitCounts, value -> value.isEmpty() ? "brak" : value),
                byText(kindCounts, value -> value.isEmpty() ? "brak" : value),
                vatCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> new Option(String.valueOf(entry.getKey()), entry.getKey() + "%", entry.getValue()))
                        .toList());
    }

    private static List<Option> byText(Map<String, Integer> counts, Function<String, String> label) {
        return counts.entrySet().stream()
                .map(entry -> new Option(entry.getKey(), label.apply(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Filtry opisane na wydruku, żeby kartka mówiła, co na niej jest. */
    private List<String> filterNotes(UUID storeId, ArticleListRequest request) {
        List<String> notes = new ArrayList<>();
        if (Boolean.TRUE.equals(request.allArticles())) {
            notes.add("Także artykuły spoza najnowszego raportu kartoteki");
        }
        if (!Boolean.TRUE.equals(request.withNotGoods())) {
            notes.add("Bez rejonów, które nie są towarem");
        }
        if (Boolean.TRUE.equals(request.withoutNomenclature())) {
            notes.add("Tylko bez nomenklatury");
        } else if (!clean(request.nomenclatures()).isEmpty()) {
            Set<String> codes = new HashSet<>(clean(request.nomenclatures()));
            notes.add("Nomenklatura: " + salesReports.nomenclatures(storeId).stream()
                    .filter(option -> codes.contains(option.code()))
                    .map(option -> option.code() + " " + option.name()).collect(Collectors.joining(", ")));
        }
        if (OTHER_CATEGORY.equals(request.category())) {
            notes.add("Kategoria: Pozostałe");
        } else if (present(request.category())) {
            categories.findActive(storeId).stream()
                    .filter(category -> category.getId().toString().equals(request.category())).findFirst()
                    .ifPresent(category -> notes.add("Kategoria: " + category.getName()));
        }
        listNote(notes, "Marka", request.brands());
        listNote(notes, "Ostatni dostawca", request.suppliers());
        if (present(request.catalogSupplier())) {
            notes.add("Dostawca w kartotece: " + request.catalogSupplier().strip());
        }
        listNote(notes, "Jednostka", request.units());
        listNote(notes, "Rodzaj", request.kinds());
        if (request.vatRates() != null && !request.vatRates().isEmpty()) {
            notes.add("VAT: " + request.vatRates().stream().map(rate -> rate + "%").collect(Collectors.joining(", ")));
        }
        if (present(request.stock())) {
            notes.add(switch (request.stock()) {
                case "POSITIVE" -> "Stan na plusie";
                case "ZERO" -> "Stan zero";
                default -> "Stan ujemny";
            });
        }
        rangeNote(notes, "Stan", request.stockFrom(), request.stockTo(), "");
        rangeNote(notes, "Rotacja", request.rotationFrom(), request.rotationTo(), "");
        rangeNote(notes, "Cena zakupu", request.purchaseFrom(), request.purchaseTo(), " zł");
        rangeNote(notes, "Cena sprzedaży", request.saleFrom(), request.saleTo(), " zł");
        rangeNote(notes, "Marża", request.marginFrom(), request.marginTo(), "%");
        rangeNote(notes, "Wartość stanu", request.valueFrom(), request.valueTo(), " zł");
        flagNote(notes, request.cadencier(), "W cadencier", "Poza cadencier");
        flagNote(notes, request.promotion(), "W promocji z kasy", "Bez promocji z kasy");
        flagNote(notes, request.deposit(), "Z kaucją", "Bez kaucji");
        flagNote(notes, request.delivery(), "Z dostawą", "Bez żadnej dostawy");
        if ("ACTIVE".equals(request.active())) {
            notes.add("Tylko aktywne");
        } else if ("INACTIVE".equals(request.active())) {
            notes.add("Tylko nieaktywne");
        }
        if (request.deliveredFrom() != null || request.deliveredTo() != null) {
            notes.add("Ostatnia dostawa " + (request.deliveredFrom() != null ? "od " + request.deliveredFrom() : "")
                    + (request.deliveredTo() != null ? " do " + request.deliveredTo() : "").strip());
        }
        if (present(request.query())) {
            notes.add("Szukane: " + request.query().strip());
        }
        return notes;
    }

    private static void listNote(List<String> notes, String label, List<String> values) {
        List<String> clean = clean(values);
        if (!clean.isEmpty()) {
            notes.add(label + ": " + String.join(", ", clean));
        }
    }

    private static void rangeNote(List<String> notes, String label, BigDecimal from, BigDecimal to, String unit) {
        if (from == null && to == null) {
            return;
        }
        notes.add(label + (from != null ? " od " + from.toPlainString() + unit : "")
                + (to != null ? " do " + to.toPlainString() + unit : ""));
    }

    private static void flagNote(List<String> notes, String mode, String with, String without) {
        if ("WITH".equals(mode)) {
            notes.add(with);
        } else if ("WITHOUT".equals(mode)) {
            notes.add(without);
        }
    }

    private String printedBy(UUID userId) {
        return userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
    }

    private static BigDecimal sum(List<Item> items, Function<Item, BigDecimal> value) {
        return items.stream().map(value).filter(amount -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::strip).toList();
    }

    /** Wartości z listy, w której pusta wartość to "brak". */
    private static Set<String> rawValues(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(ArticleListService::valueOrEmpty).collect(Collectors.toSet());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(word);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
