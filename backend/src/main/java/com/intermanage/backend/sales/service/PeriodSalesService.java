package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.Percent;
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.LastSupplier;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.component.PeriodSalesPdf;
import com.intermanage.backend.sales.component.TopSalesPdf;
import com.intermanage.backend.sales.dto.PeriodSalesDepth;
import com.intermanage.backend.sales.dto.PeriodSalesReport;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Article;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Brand;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Figures;
import com.intermanage.backend.sales.dto.PeriodSalesReport.Node;
import com.intermanage.backend.sales.dto.PeriodSalesRequest;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SaleRepository.ArticleSales;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Sprzedaż za okres bez porównania: cała sprzedaż sklepu po nomenklaturze (rejon → rodzina → podrodzina →
 * artykuł), zawężona filtrami nomenklatury, marki, ostatniego dostawcy i wyszukiwania. Sumy każdej pozycji liczą się
 * tylko z artykułów, które przeszły filtry, więc ekran i wydruk pokazują tylko wyfiltrowaną sprzedaż. Wydruk schodzi
 * do wybranego poziomu.
 * <p>
 * Pozycja „Bez nomenklatury” zbiera sprzedaż artykułów spoza kartoteki i bez podrodziny. Marka jak w topach: z
 * kartoteki, bez wielkości liter i nadmiarowych spacji; dostawca to dostawca ostatniego przyjęcia PZ artykułu.
 */
@Service
public class PeriodSalesService {

    /** Kod rozwijanej pozycji „Bez nomenklatury”. */
    public static final String UNASSIGNED_PARENT = "brak";
    private static final String UNASSIGNED = "";
    private static final int SUBFAMILY_LENGTH = 6;
    private static final int SUPPLIER_BATCH = 5000;

    private final SaleRepository sales;
    private final NomenclatureRepository nomenclatures;
    private final DocumentLineRepository lines;
    private final StoreRepository stores;
    private final SupplierRepository supplierList;
    private final AppUserRepository users;
    private final PeriodSalesPdf reportPdf;

    public PeriodSalesService(SaleRepository sales, NomenclatureRepository nomenclatures,
            DocumentLineRepository lines, StoreRepository stores, SupplierRepository supplierList,
            AppUserRepository users, PeriodSalesPdf reportPdf) {
        this.sales = sales;
        this.nomenclatures = nomenclatures;
        this.lines = lines;
        this.stores = stores;
        this.supplierList = supplierList;
        this.users = users;
        this.reportPdf = reportPdf;
    }

    @Transactional(readOnly = true)
    public PeriodSalesReport report(UUID storeId, PeriodSalesRequest request) {
        return compute(storeId, request).report();
    }

    /** Artykuły jednej podrodziny ({@code parent}) albo pozycji „Bez nomenklatury”, od największej sprzedaży. */
    @Transactional(readOnly = true)
    public List<Article> articles(UUID storeId, PeriodSalesRequest request) {
        check(request);
        String parent = request.parent() == null ? "" : request.parent().trim();
        boolean unassigned = UNASSIGNED_PARENT.equals(parent);
        if (!unassigned && !parent.matches("\\d{" + SUBFAMILY_LENGTH + "}")) {
            throw ApiException.badRequest("Podaj podrodzinę do rozwinięcia");
        }
        sales.tuneForReport();
        List<ArticleSales> found = unassigned ? sales.sumArticles(storeId, request.from(), request.to())
                : sales.sumGroupArticles(storeId, request.from(), request.to(), parent);
        String key = unassigned ? UNASSIGNED : parent;
        List<ArticleSales> chosen = filtered(storeId, found.stream()
                .filter(article -> subfamilyOf(article).equals(key)).toList(), request);
        return chosen.stream().sorted(BY_SALE).map(PeriodSalesService::article).toList();
    }

    @Transactional(readOnly = true)
    public ExportFile pdf(UUID storeId, PeriodSalesRequest request, UUID userId) {
        Computed computed = compute(storeId, request);
        if (computed.articles().isEmpty()) {
            throw ApiException.badRequest("Brak sprzedaży do wydruku");
        }
        PeriodSalesDepth depth = request.depth() == null ? PeriodSalesDepth.FAMILY : request.depth();
        Map<String, List<Article>> articles = new HashMap<>();
        if (depth == PeriodSalesDepth.ARTICLE) {
            computed.articles().stream().sorted(BY_SALE).forEach(article -> articles
                    .computeIfAbsent(subfamilyOf(article), code -> new ArrayList<>()).add(article(article)));
        }
        Store store = stores.findById(storeId).orElseThrow();
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        byte[] content = reportPdf.pdf(store.getNumber(), store.getName(), computed.report(), depth, articles,
                filterNotes(storeId, request, computed.names()), printedBy);
        return new ExportFile("sprzedaz-" + store.getNumber() + "-" + request.from() + "-" + request.to() + ".pdf",
                "application/pdf", content);
    }

    private Computed compute(UUID storeId, PeriodSalesRequest request) {
        check(request);
        sales.tuneForReport();
        List<String> codes = present(request.nomenclatures());
        List<ArticleSales> inNomenclature = sales.sumArticles(storeId, request.from(), request.to()).stream()
                .filter(article -> codes.isEmpty() || (article.getSubfamilyCode() != null
                        && codes.stream().anyMatch(article.getSubfamilyCode()::startsWith)))
                .toList();
        Map<String, Integer> brandCounts = new TreeMap<>();
        inNomenclature.stream().map(article -> TopSalesService.brandKey(article.getBrand()))
                .filter(key -> !key.isEmpty()).forEach(key -> brandCounts.merge(key, 1, Integer::sum));
        List<Brand> brands = brandCounts.entrySet().stream()
                .map(entry -> new Brand(entry.getKey(), entry.getValue())).toList();
        List<ArticleSales> articles = filtered(storeId, inNomenclature, request);

        Map<String, String> names = nomenclatures.findAllByStore(storeId).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (first, second) -> first));
        Map<String, List<ArticleSales>> bySubfamily = new TreeMap<>(CODE_ORDER);
        articles.forEach(article -> bySubfamily.computeIfAbsent(subfamilyOf(article), code -> new ArrayList<>())
                .add(article));
        Map<String, Map<String, Map<String, List<ArticleSales>>>> tree = new TreeMap<>(CODE_ORDER);
        bySubfamily.forEach((subfamily, group) -> {
            String region = subfamily.isEmpty() ? UNASSIGNED : subfamily.substring(0, 2);
            String family = subfamily.isEmpty() ? UNASSIGNED : subfamily.substring(0, 4);
            tree.computeIfAbsent(region, code -> new TreeMap<>(CODE_ORDER))
                    .computeIfAbsent(family, code -> new TreeMap<>(CODE_ORDER)).put(subfamily, group);
        });
        List<Node> regions = new ArrayList<>();
        tree.forEach((region, families) -> {
            if (region.isEmpty()) {
                List<ArticleSales> all = families.values().stream().flatMap(map -> map.values().stream())
                        .flatMap(List::stream).toList();
                regions.add(new Node(UNASSIGNED, null, "REGION", figures(all), all.size(), List.of()));
                return;
            }
            List<Node> familyNodes = new ArrayList<>();
            List<ArticleSales> inRegion = new ArrayList<>();
            families.forEach((family, subfamilies) -> {
                List<Node> subfamilyNodes = new ArrayList<>();
                List<ArticleSales> inFamily = new ArrayList<>();
                subfamilies.forEach((subfamily, group) -> {
                    subfamilyNodes.add(new Node(subfamily, names.get(subfamily), "SUBFAMILY", figures(group),
                            group.size(), List.of()));
                    inFamily.addAll(group);
                });
                familyNodes.add(new Node(family, names.get(family), "FAMILY", figures(inFamily), inFamily.size(),
                        subfamilyNodes));
                inRegion.addAll(inFamily);
            });
            regions.add(new Node(region, names.get(region), "REGION", figures(inRegion), inRegion.size(),
                    familyNodes));
        });
        PeriodSalesReport report = new PeriodSalesReport(request.from(), request.to(), figures(articles),
                articles.size(), brands, regions);
        return new Computed(report, articles, names);
    }

    /** Filtry marki, dostawcy i wyszukiwania; nomenklatura jest sprawdzana osobno. */
    private List<ArticleSales> filtered(UUID storeId, List<ArticleSales> articles, PeriodSalesRequest request) {
        Set<String> brands = present(request.brands()).stream().map(TopSalesService::brandKey)
                .filter(key -> !key.isEmpty()).collect(Collectors.toSet());
        List<String> words = request.query() == null ? List.of()
                : List.of(normalize(request.query()).split("\\s+")).stream().filter(word -> !word.isEmpty()).toList();
        List<ArticleSales> result = articles.stream()
                .filter(article -> brands.isEmpty() || brands.contains(TopSalesService.brandKey(article.getBrand())))
                .filter(article -> words.isEmpty() || matches(article, words))
                .toList();
        Set<String> suppliers = present(request.suppliers()).stream().map(String::trim).collect(Collectors.toSet());
        if (suppliers.isEmpty() || result.isEmpty()) {
            return result;
        }
        List<String> itm8s = result.stream().map(ArticleSales::getItm8).toList();
        Set<String> supplied = new HashSet<>();
        for (int start = 0; start < itm8s.size(); start += SUPPLIER_BATCH) {
            lines.findLastSuppliers(storeId, itm8s.subList(start, Math.min(itm8s.size(), start + SUPPLIER_BATCH)))
                    .stream().filter(last -> suppliers.contains(last.getSupplierCode().trim()))
                    .map(LastSupplier::getItm8).forEach(supplied::add);
        }
        return result.stream().filter(article -> supplied.contains(article.getItm8())).toList();
    }

    private static boolean matches(ArticleSales article, List<String> words) {
        String text = normalize(String.join(" ", nullToEmpty(article.getName()), nullToEmpty(article.getBrand()),
                nullToEmpty(article.getEan()), article.getItm8(), article.getItm8().replaceFirst("^0+", "")));
        return words.stream().allMatch(text::contains);
    }

    private List<String> filterNotes(UUID storeId, PeriodSalesRequest request, Map<String, String> names) {
        List<String> notes = new ArrayList<>();
        List<String> codes = present(request.nomenclatures());
        if (!codes.isEmpty()) {
            notes.add("nomenklatura: " + codes.stream().distinct().sorted()
                    .map(code -> TopSalesPdf.spaced(code)
                            + (names.containsKey(code) ? " " + TopSalesPdf.nomenclatureName(names.get(code)) : ""))
                    .collect(Collectors.joining(", ")));
        }
        List<String> brands = present(request.brands());
        if (!brands.isEmpty()) {
            notes.add("marka: " + brands.stream().map(TopSalesService::brandKey).distinct().sorted()
                    .collect(Collectors.joining(", ")));
        }
        List<String> suppliers = present(request.suppliers());
        if (!suppliers.isEmpty()) {
            Map<String, String> supplierNames = new HashMap<>();
            supplierList.findByStore(storeId).stream().filter(supplier -> supplier.getCode() != null)
                    .forEach(supplier -> supplierNames.putIfAbsent(supplier.getCode().trim(), supplier.getName()));
            notes.add("dostawca: " + suppliers.stream().map(String::trim).distinct().sorted()
                    .map(code -> code + (supplierNames.containsKey(code) ? " " + supplierNames.get(code) : ""))
                    .collect(Collectors.joining(", ")));
        }
        if (request.query() != null && !request.query().isBlank()) {
            notes.add("szukane: „" + request.query().trim() + "”");
        }
        return notes;
    }

    private static void check(PeriodSalesRequest request) {
        if (request.from() == null || request.to() == null) {
            throw ApiException.badRequest("Podaj okres");
        }
        ReportPeriod.check(request.from(), request.to());
    }

    private static String subfamilyOf(ArticleSales article) {
        String code = article.getSubfamilyCode();
        return code == null || code.length() != SUBFAMILY_LENGTH ? UNASSIGNED : code;
    }

    private static Article article(ArticleSales article) {
        return new Article(article.getItm8(), article.getEan(), article.getName(), article.getBrand(),
                figures(List.of(article)));
    }

    private static Figures figures(List<ArticleSales> articles) {
        BigDecimal gross = sum(articles, ArticleSales::getSaleValueGross);
        BigDecimal margin = sum(articles, ArticleSales::getMarginValue);
        return new Figures(sum(articles, ArticleSales::getQuantity), sum(articles, ArticleSales::getCostValue), gross,
                sum(articles, ArticleSales::getSaleValueNet), margin, Percent.of(margin, gross));
    }

    private static BigDecimal sum(List<ArticleSales> articles, Function<ArticleSales, BigDecimal> value) {
        return articles.stream().map(value).filter(amount -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> present(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Wielkie litery bez polskich znaków, do porównania słów wyszukiwania. */
    static String normalize(String value) {
        String plain = Normalizer.normalize(value.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.toUpperCase(Locale.ROOT).trim();
    }

    /** Kody rosnąco, pusty („Bez nomenklatury”) na końcu. */
    private static final Comparator<String> CODE_ORDER =
            Comparator.comparing(String::isEmpty).thenComparing(Comparator.naturalOrder());

    private static final Comparator<ArticleSales> BY_SALE = Comparator
            .comparing((ArticleSales article) -> article.getSaleValueGross() == null ? BigDecimal.ZERO
                    : article.getSaleValueGross())
            .reversed().thenComparing(ArticleSales::getItm8);

    private record Computed(PeriodSalesReport report, List<ArticleSales> articles, Map<String, String> names) {
    }
}
