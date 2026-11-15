package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import com.intermanage.backend.document.repository.DocumentLineRepository.ArticleLoss;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.product.dto.ArticleLinks;
import com.intermanage.backend.product.service.ArticleStoreLinkService;
import com.intermanage.backend.sales.component.CounterReportPdf;
import com.intermanage.backend.sales.dto.CounterReport;
import com.intermanage.backend.sales.dto.CounterReport.Article;
import com.intermanage.backend.sales.dto.CounterReport.Family;
import com.intermanage.backend.sales.dto.CounterReport.Figures;
import com.intermanage.backend.sales.dto.CounterReport.Metric;
import com.intermanage.backend.sales.dto.CounterReport.Option;
import com.intermanage.backend.sales.dto.CounterReport.Region;
import com.intermanage.backend.sales.dto.CounterReport.Subfamily;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SaleRepository.ArticleSales;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.service.StoreService;

/**
 * Raport lady (następca arkusza „36 szczegółowo”): sprzedaż rejonów lady {@link #REGIONS} (mięso, garmaż, drób) w
 * kilku sklepach naraz: ilość, zakup FIFO, sprzedaż, marża zł i % oraz udział pozycji w sprzedaży wybranych rejonów
 * sklepu, według rejonów, rodzin, podrodzin i artykułów.
 * <p>
 * Porównać można sklepy, do których użytkownik ma dostęp, i sklepy zaprzyjaźnione z bieżącym; najwyżej
 * {@value #MAX_STORES} naraz, bieżący zawsze pierwszy. Artykuły łączą się po ITM8 i po połączeniach z
 * {@link ArticleStoreLinkService} (lokalne kody {@code 99…} każdego sklepu są inne).
 */
@Service
public class CounterReportService {

    /** Rejony lady w kolejności raportu. */
    public static final List<String> REGIONS = List.of("24", "36", "46");
    public static final int MAX_STORES = 6;
    static final List<Metric> DEFAULT_METRICS = List.of(Metric.QUANTITY, Metric.COST, Metric.SALE, Metric.MARGIN,
            Metric.MARGIN_PERCENT);

    private final SaleRepository sales;
    private final NomenclatureRepository nomenclatures;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final StoreService storeService;
    private final AppUserRepository users;
    private final CounterReportPdf pdf;
    private final ArticleStoreLinkService articleLinks;
    private final DocumentLineRepository documentLines;

    public CounterReportService(SaleRepository sales, NomenclatureRepository nomenclatures, StoreRepository stores,
            StorePartnerRepository partners, StoreService storeService, AppUserRepository users,
            CounterReportPdf pdf, ArticleStoreLinkService articleLinks, DocumentLineRepository documentLines) {
        this.sales = sales;
        this.nomenclatures = nomenclatures;
        this.stores = stores;
        this.partners = partners;
        this.storeService = storeService;
        this.users = users;
        this.pdf = pdf;
        this.articleLinks = articleLinks;
        this.documentLines = documentLines;
    }

    /** Sklepy do porównania z bieżącym: dostępne użytkownikowi, potem zaprzyjaźnione (bez bieżącego). */
    @Transactional(readOnly = true)
    public List<Option> options(UUID storeId, UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.forbidden("Brak dostępu"));
        Map<UUID, Option> result = new LinkedHashMap<>();
        for (StoreSummary store : storeService.accessibleStores(user)) {
            if (!store.id().equals(storeId)) {
                result.put(store.id(), new Option(store.id(), store.number(), store.name(), false));
            }
        }
        partners.findActive(storeId).stream()
                .filter(pair -> pair.getAcceptedAt() != null)
                .map(pair -> pair.getStore().getId().equals(storeId) ? pair.getPartnerStore() : pair.getStore())
                .filter(Store::isActive)
                .sorted(Comparator.comparing(Store::getNumber))
                .forEach(store -> result.putIfAbsent(store.getId(),
                        new Option(store.getId(), store.getNumber(), store.getName(), true)));
        return List.copyOf(result.values());
    }

    /** {@code regions} puste = wszystkie rejony lady; rejon spoza {@link #REGIONS} daje 400. */
    @Transactional(readOnly = true)
    public CounterReport report(UUID storeId, UUID userId, LocalDate from, LocalDate to, List<String> regions,
            List<UUID> compare) {
        ReportPeriod.check(from, to);
        List<String> codes = regions(regions);
        List<UUID> ids = storeIds(storeId, userId, compare);
        Map<UUID, Store> byId = stores.findAllById(ids).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));

        sales.tuneForReport();
        List<List<ArticleSales>> perStore = new ArrayList<>();
        List<List<ArticleLoss>> lossesPerStore = new ArrayList<>();
        List<CounterReport.Store> shown = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (UUID id : ids) {
            Store store = byId.get(id);
            List<ArticleSales> articles = new ArrayList<>();
            for (String code : codes) {
                articles.addAll(sales.sumGroupArticles(id, from, to, code));
            }
            perStore.add(articles);
            List<ArticleLoss> losses = new ArrayList<>();
            for (String code : codes) {
                losses.addAll(documentLines.sumArticleLosses(id, code, from, to));
            }
            lossesPerStore.add(losses);
            shown.add(new CounterReport.Store(id, store.getNumber(), store.getName(),
                    sales.findCoverage(id).getLastDate()));
            for (Nomenclature item : nomenclatures.findAllByStore(id)) {
                if (codes.stream().anyMatch(item.getCode()::startsWith)) {
                    names.putIfAbsent(item.getCode(), item.getName().replaceFirst("^\\d+_", "").strip());
                }
            }
        }
        return build(from, to, codes, names, shown, perStore, lossesPerStore, articleLinks.between(ids));
    }

    /** Wydruk (PDF) z wybranymi miarami; artykuły tylko na życzenie, bo wydłużają wydruk do kilku stron. */
    @Transactional(readOnly = true)
    public PeriodSummaryService.ExportFile pdf(UUID storeId, UUID userId, LocalDate from, LocalDate to,
            List<String> regions, List<UUID> compare, List<Metric> metrics, CounterReport.Basis basis,
            boolean withArticles) {
        CounterReport report = report(storeId, userId, from, to, regions, compare);
        List<Metric> shown = metrics == null || metrics.isEmpty() ? DEFAULT_METRICS
                : List.copyOf(new LinkedHashSet<>(metrics));
        String printedBy = users.findById(userId).map(AppUser::getDisplayName).orElse(null);
        return new PeriodSummaryService.ExportFile(
                "raport-lady-" + report.stores().getFirst().number() + "-" + from + "-" + to + ".pdf",
                "application/pdf",
                pdf.pdf(report, shown, basis == null ? CounterReport.Basis.GROSS : basis, withArticles, printedBy));
    }

    static List<String> regions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return REGIONS;
        }
        if (regions.stream().anyMatch(code -> !REGIONS.contains(code))) {
            throw ApiException.badRequest("Raport lady obejmuje tylko rejony " + String.join(", ", REGIONS));
        }
        return REGIONS.stream().filter(regions::contains).toList();
    }

    /** Bieżący sklep i wybrane do porównania (bez powtórzeń); sklep spoza listy do wyboru daje 403. */
    private List<UUID> storeIds(UUID storeId, UUID userId, List<UUID> compare) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(storeId);
        if (compare != null) {
            ids.addAll(compare);
        }
        if (ids.size() > MAX_STORES) {
            throw ApiException.badRequest("Porównać można najwyżej " + MAX_STORES + " sklepów naraz");
        }
        if (ids.size() > 1) {
            Set<UUID> allowed = options(storeId, userId).stream().map(Option::id).collect(Collectors.toSet());
            if (ids.stream().skip(1).anyMatch(id -> !allowed.contains(id))) {
                throw ApiException.forbidden("Porównać można tylko swoje sklepy i sklepy zaprzyjaźnione");
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Drzewo raportu. Wiersz artykułu ma klucz z {@code links} (ten sam ITM8 albo połączone artykuły sklepów), a
     * stoi w podrodzinie artykułu pierwszego sklepu, który go sprzedał, więc połączony towar sąsiada z inną
     * nomenklaturą nie robi drugiego wiersza.
     */
    public static CounterReport build(LocalDate from, LocalDate to, List<String> codes, Map<String, String> names,
            List<CounterReport.Store> shown, List<List<ArticleSales>> perStore, ArticleLinks links) {
        return build(from, to, codes, names, shown, perStore, shown.stream().map(store -> List.<ArticleLoss>of()).toList(),
                links);
    }

    /**
     * Jak wyżej, z likwidacjami (PL) każdego sklepu: odejmują się od marży pozycji, a artykuł zlikwidowany bez
     * sprzedaży w okresie też ma wiersz (marża na minusie).
     */
    public static CounterReport build(LocalDate from, LocalDate to, List<String> codes, Map<String, String> names,
            List<CounterReport.Store> shown, List<List<ArticleSales>> perStore, List<List<ArticleLoss>> losses,
            ArticleLinks links) {
        int count = shown.size();
        Map<String, String> subfamilies = new HashMap<>();
        for (int store = 0; store < count; store++) {
            UUID storeId = shown.get(store).id();
            for (ArticleSales sale : perStore.get(store)) {
                subfamilies.putIfAbsent(links.key(storeId, sale.getItm8()), sale.getSubfamilyCode());
            }
        }
        for (int store = 0; store < count; store++) {
            UUID storeId = shown.get(store).id();
            for (ArticleLoss loss : losses.get(store)) {
                subfamilies.putIfAbsent(links.key(storeId, loss.getItm8()), loss.getSubfamilyCode());
            }
        }
        Node total = new Node(count, null, null);
        for (int store = 0; store < count; store++) {
            UUID storeId = shown.get(store).id();
            for (ArticleSales sale : perStore.get(store)) {
                String key = links.key(storeId, sale.getItm8());
                String subfamily = subfamilies.get(key);
                Node node = total;
                node.add(store, sale);
                for (int length : new int[] {2, 4, 6}) {
                    String code = subfamily.length() > length ? subfamily.substring(0, length) : subfamily;
                    node = node.child(code, count, null, null);
                    node.add(store, sale);
                }
                Node article = node.child(key, count, sale.getName(), sale.getBrand());
                article.add(store, sale);
                article.source(store, sale.getItm8(), sale.getName(), links.linkId(storeId, sale.getItm8()));
            }
            for (ArticleLoss loss : losses.get(store)) {
                String key = links.key(storeId, loss.getItm8());
                String subfamily = subfamilies.get(key);
                Node node = total;
                node.addLoss(store, loss.getLossValue());
                for (int length : new int[] {2, 4, 6}) {
                    String code = subfamily.length() > length ? subfamily.substring(0, length) : subfamily;
                    node = node.child(code, count, null, null);
                    node.addLoss(store, loss.getLossValue());
                }
                Node article = node.child(key, count, loss.getName(), loss.getBrand());
                article.addLoss(store, loss.getLossValue());
                article.source(store, loss.getItm8(), loss.getName(), links.linkId(storeId, loss.getItm8()));
            }
        }
        List<Region> regions = total.sortedByCode().stream()
                .map(region -> new Region(region.code, names.get(region.code), region.figures(total),
                        region.sortedByCode().stream()
                                .map(family -> new Family(family.code, names.get(family.code), family.figures(total),
                                        family.sortedByCode().stream()
                                                .map(subfamily -> new Subfamily(subfamily.code,
                                                        names.get(subfamily.code), subfamily.figures(total),
                                                        subfamily.children.values().stream()
                                                                .sorted(Comparator.comparing(Node::grossTotal)
                                                                        .reversed())
                                                                .map(article -> new Article(article.code,
                                                                        article.name, article.brand,
                                                                        article.figures(total),
                                                                        Arrays.asList(article.sources)))
                                                                .toList()))
                                                .toList()))
                                .toList()))
                .toList();
        return new CounterReport(from, to, codes, shown, total.figures(total), regions);
    }

    /** Pozycja drzewa z sumami w każdym sklepie; sklep bez żadnej sprzedaży pozycji ma {@code null}. */
    private static final class Node {

        final String code;
        final String name;
        final String brand;
        final Sum[] stores;
        final CounterReport.Source[] sources;
        final Map<String, Node> children = new HashMap<>();

        Node(int count, String code, String name) {
            this(count, code, name, null);
        }

        Node(int count, String code, String name, String brand) {
            this.code = code;
            this.name = name;
            this.brand = brand;
            this.stores = new Sum[count];
            this.sources = new CounterReport.Source[count];
        }

        /** Pierwszy artykuł sklepu w wierszu (przy połączeniu łańcuchowym sklep może mieć dwa: sumy łączą oba). */
        void source(int store, String itm8, String name, UUID linkId) {
            if (sources[store] == null) {
                sources[store] = new CounterReport.Source(itm8, name, linkId);
            }
        }

        void addLoss(int store, BigDecimal value) {
            if (stores[store] == null) {
                stores[store] = new Sum();
            }
            stores[store].loss = stores[store].loss.add(value);
        }

        Node child(String code, int count, String name, String brand) {
            return children.computeIfAbsent(code, key -> new Node(count, key, name, brand));
        }

        List<Node> sortedByCode() {
            return new TreeMap<>(children).values().stream().toList();
        }

        void add(int store, ArticleSales sale) {
            if (stores[store] == null) {
                stores[store] = new Sum();
            }
            Sum sum = stores[store];
            sum.quantity = sum.quantity.add(sale.getQuantity());
            sum.cost = sum.cost.add(sale.getCostValue());
            sum.gross = sum.gross.add(sale.getSaleValueGross());
            sum.net = sum.net.add(sale.getSaleValueNet());
            sum.margin = sum.margin.add(sale.getMarginValue());
        }

        BigDecimal grossTotal() {
            BigDecimal result = BigDecimal.ZERO;
            for (Sum sum : stores) {
                if (sum != null) {
                    result = result.add(sum.gross);
                }
            }
            return result;
        }

        List<Figures> figures(Node total) {
            List<Figures> result = new ArrayList<>(stores.length);
            for (int store = 0; store < stores.length; store++) {
                Sum sum = stores[store];
                Sum whole = total.stores[store];
                BigDecimal margin = sum == null ? null : sum.margin.subtract(sum.loss);
                result.add(sum == null ? null
                        : new Figures(sum.quantity, sum.cost, sum.gross, sum.net, margin,
                                Percent.of(margin, sum.gross),
                                whole == null ? null : Percent.of(sum.gross, whole.gross), sum.loss));
            }
            return result;
        }
    }

    private static final class Sum {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
    }
}
