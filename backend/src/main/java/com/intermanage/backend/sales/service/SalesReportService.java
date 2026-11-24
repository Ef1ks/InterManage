package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.service.DataEntryService;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.nomenclature.mapper.NomenclatureMapper;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ArticleMergeRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.promotion.service.PromotionReportService;
import com.intermanage.backend.mea.entity.MeaDocument;
import com.intermanage.backend.mea.repository.MeaDocumentRepository;
import com.intermanage.backend.sales.dto.ArticleDeliveryResponse;
import com.intermanage.backend.sales.dto.ArticleLeafletMark;
import com.intermanage.backend.sales.dto.ArticleProtocolResponse;
import com.intermanage.backend.sales.dto.ArticleRowsPage;
import com.intermanage.backend.cadtop.dto.CadPrices;
import com.intermanage.backend.cadtop.service.CadPriceService;
import com.intermanage.backend.sales.dto.SalesArticleResponse;
import com.intermanage.backend.sales.dto.SalesComparisonResponse.Level;
import com.intermanage.backend.sales.dto.SalesComparisonResponse;
import com.intermanage.backend.sales.dto.SalesComparisonRow;
import com.intermanage.backend.sales.dto.SalesFigures;
import com.intermanage.backend.sales.dto.SalesHistoryDay;
import com.intermanage.backend.sales.dto.SalesHistoryResponse;
import com.intermanage.backend.sales.dto.SalesPromotionShare;
import com.intermanage.backend.sales.dto.SalesReportsOverview;
import com.intermanage.backend.sales.mapper.SalesMapper;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SalesImportRepository;

/**
 * Raporty z zapisanej sprzedaży. Porównanie sprzedaży zestawia dwa okresy (poprzedni i aktualny) jako całość, bez
 * podziału na dni, i schodzi po drzewie nomenklatury: rejony → rodziny → podrodziny → artykuły. Każdy poziom to
 * osobne zapytanie, liczone w locie z tabeli sprzedaży (miesiąc do miesiąca to ok. 100 tys. wierszy, rok ok. 1,5 mln).
 * Do tego wykres sprzedaży dzień po dniu (artykułu albo pozycji nomenklatury) i dane artykułu z kartoteki.
 */
@Service
@Transactional(readOnly = true)
public class SalesReportService {

    /** Najdłuższy okres porównania: rok do roku, także w latach przestępnych. */

    /** Rozwinięta pozycja: nic (rejony sklepu), rejon, rodzina albo podrodzina. */
    private static final Pattern PARENT = Pattern.compile("([0-9]{2}){0,3}");

    /** Ile ostatnich dostaw pokazuje okno artykułu. */
    static final int DELIVERY_LIMIT = 50;
    static final int PROTOCOL_LIMIT = 20;

    /** Pozycja nomenklatury w filtrze: rejon, rodzina albo podrodzina. */
    private static final Pattern FILTER_CODE = Pattern.compile("([0-9]{2}){1,3}");

    private final SaleRepository sales;
    private final SalesImportRepository imports;
    private final ProductRepository products;
    private final NomenclatureRepository nomenclatures;
    private final SalesMapper salesMapper;
    private final NomenclatureMapper nomenclatureMapper;
    private final DocumentLineRepository documentLines;
    private final CadencierImportRepository cadencier;
    private final CounterReportService counterReports;
    private final MeaDocumentRepository meaDocuments;
    private final ArticleMergeRepository merges;
    private final CadPriceService cadPrices;

    public SalesReportService(SaleRepository sales, SalesImportRepository imports, ProductRepository products,
            NomenclatureRepository nomenclatures, SalesMapper salesMapper, NomenclatureMapper nomenclatureMapper,
            DocumentLineRepository documentLines, CadencierImportRepository cadencier,
            CounterReportService counterReports, MeaDocumentRepository meaDocuments, ArticleMergeRepository merges,
            CadPriceService cadPrices) {
        this.cadPrices = cadPrices;
        this.meaDocuments = meaDocuments;
        this.merges = merges;
        this.cadencier = cadencier;
        this.counterReports = counterReports;
        this.sales = sales;
        this.imports = imports;
        this.products = products;
        this.nomenclatures = nomenclatures;
        this.salesMapper = salesMapper;
        this.nomenclatureMapper = nomenclatureMapper;
        this.documentLines = documentLines;
    }

    public SalesReportsOverview overview(UUID storeId) {
        SaleRepository.Coverage coverage = sales.findCoverage(storeId);
        return new SalesReportsOverview(coverage.getFirstDate(), coverage.getLastDate(),
                imports.findLastReportGeneratedAt(storeId).orElse(null));
    }

    /**
     * Jeden poziom porównania pod {@code parent}: pusty tekst daje rejony, kod rejonu jego rodziny, kod rodziny
     * podrodziny, a kod podrodziny artykuły. {@code unassigned} daje artykuły bez nomenklatury (spoza kartoteki).
     * Niepuste {@code filter} zawęża porównanie do artykułów z wybranych pozycji nomenklatury (dowolnego poziomu).
     */
    public SalesComparisonResponse compare(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String parent, boolean unassigned, List<String> filter) {
        return compare(storeId, previousFrom, previousTo, currentFrom, currentTo, parent, unassigned, filter, null,
                null);
    }

    /**
     * Jak wyżej, a z {@code otherStoreId} każdy wiersz dostaje sprzedaż drugiego sklepu w aktualnym okresie (ten sam
     * kod nomenklatury albo ITM8). Pozycje, które sprzedał tylko drugi sklep, dochodzą z zerami u nas. Drugi sklep
     * musi być dostępny użytkownikowi albo zaprzyjaźniony (jak w raporcie lady), inaczej 403.
     */
    public SalesComparisonResponse compare(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String parent, boolean unassigned, List<String> filter,
            UUID otherStoreId, UUID userId) {
        ReportPeriod.check(previousFrom, previousTo, ReportPeriod.MAX_DAYS, "Poprzedni okres");
        ReportPeriod.check(currentFrom, currentTo, ReportPeriod.MAX_DAYS, "Aktualny okres");
        String code = parentCode(parent, unassigned);
        String[] codes = filterCodes(filter);
        boolean withOther = otherStoreId != null && !otherStoreId.equals(storeId);
        if (withOther && counterReports.options(storeId, userId).stream()
                .noneMatch(option -> option.id().equals(otherStoreId))) {
            throw ApiException.forbidden("Porównać można tylko swoje sklepy i sklepy zaprzyjaźnione");
        }

        sales.tuneForReport();
        Level level = level(code, unassigned);
        List<SalesComparisonRow> rows = query(storeId, previousFrom, previousTo, currentFrom, currentTo, code,
                unassigned, codes).stream().map(SalesReportService::toRow).toList();
        SalesFigures otherTotal = null;
        if (withOther) {
            // Drugi sklep tylko w aktualnym okresie: poprzedni to jeden dzień bez sprzedaży, więc nic nie czyta.
            List<SalesComparisonRow> theirs = query(otherStoreId, NO_SALES_DAY, NO_SALES_DAY, currentFrom, currentTo,
                    code, unassigned, codes).stream().map(SalesReportService::toRow).toList();
            rows = withOther(rows, theirs, level);
            otherTotal = rows.stream().map(SalesComparisonRow::other).reduce(SalesFigures.ZERO, SalesFigures::plus);
        }
        if (level == Level.PRODUCT) {
            rows = withLeaflets(storeId, rows, previousFrom, previousTo, currentFrom, currentTo);
        }
        SalesFigures previousTotal = rows.stream().map(SalesComparisonRow::previous)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        SalesFigures currentTotal = rows.stream().map(SalesComparisonRow::current)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo,
                unassigned ? null : code, level, rows, previousTotal, currentTotal, otherTotal);
    }

    /**
     * Artykuły z gazetkami (MEA i gazetki sklepu), które nachodzą na okres porównania albo aktualny. Gazetka sklepu
     * z tym samym okresem co MEA artykułu to zwykle ta sama promocja (plik ma NR MEA), więc zostaje tylko MEA.
     */
    private List<SalesComparisonRow> withLeaflets(UUID storeId, List<SalesComparisonRow> rows,
            LocalDate previousFrom, LocalDate previousTo, LocalDate currentFrom, LocalDate currentTo) {
        String[] itm8s = rows.stream().map(SalesComparisonRow::code).filter(code -> !code.isEmpty()).distinct()
                .toArray(String[]::new);
        if (itm8s.length == 0) {
            return rows;
        }
        LocalDate from = previousFrom.isBefore(currentFrom) ? previousFrom : currentFrom;
        LocalDate to = previousTo.isAfter(currentTo) ? previousTo : currentTo;
        Map<String, List<ArticleLeafletMark>> byArticle = new HashMap<>();
        for (MeaDocumentRepository.ArticleLeaflet leaflet : meaDocuments.findArticleLeaflets(storeId, itm8s, from,
                to)) {
            boolean previous = overlaps(leaflet, previousFrom, previousTo);
            boolean current = overlaps(leaflet, currentFrom, currentTo);
            if (!previous && !current) {
                continue;
            }
            List<ArticleLeafletMark> marks = byArticle.computeIfAbsent(leaflet.getItm8(), key -> new ArrayList<>());
            boolean sameAsMea = leaflet.getNumber() == null && marks.stream().anyMatch(mark -> mark.number() != null
                    && mark.from().equals(leaflet.getPromoFrom()) && mark.to().equals(leaflet.getPromoTo()));
            if (!sameAsMea) {
                marks.add(new ArticleLeafletMark(leaflet.getNumber(), leaflet.getTitle(), leaflet.getPromoFrom(),
                        leaflet.getPromoTo(), previous, current));
            }
        }
        if (byArticle.isEmpty()) {
            return rows;
        }
        return rows.stream().map(row -> {
            List<ArticleLeafletMark> marks = byArticle.get(row.code());
            return marks == null ? row : row.withLeaflets(List.copyOf(marks));
        }).toList();
    }

    private static boolean overlaps(MeaDocumentRepository.ArticleLeaflet leaflet, LocalDate from, LocalDate to) {
        return leaflet.getPromoFrom() != null && leaflet.getPromoTo() != null
                && !leaflet.getPromoFrom().isAfter(to) && !leaflet.getPromoTo().isBefore(from);
    }

    /**
     * Ile sprzedaży brutto i marży obu okresów dały artykuły w dniach promocji (lista promocji, gazetki, MEA, cena poniżej
     * zwykłej), z tym samym filtrem nomenklatury co porównanie. Osobne zapytanie, bo jest wolniejsze od samego
     * porównania (do kilku sekund rok do roku), więc kafelek nie wstrzymuje tabeli.
     */
    public SalesPromotionShare promotionShare(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, List<String> filter) {
        ReportPeriod.check(previousFrom, previousTo, ReportPeriod.MAX_DAYS, "Poprzedni okres");
        ReportPeriod.check(currentFrom, currentTo, ReportPeriod.MAX_DAYS, "Aktualny okres");
        String[] codes = filterCodes(filter);
        sales.tuneForReport();
        LocalDate from = previousFrom.isBefore(currentFrom) ? previousFrom : currentFrom;
        LocalDate to = previousTo.isAfter(currentTo) ? previousTo : currentTo;
        BigDecimal previous = BigDecimal.ZERO;
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal previousNet = BigDecimal.ZERO;
        BigDecimal currentNet = BigDecimal.ZERO;
        BigDecimal previousMargin = BigDecimal.ZERO;
        BigDecimal currentMargin = BigDecimal.ZERO;
        long previousArticles = 0;
        long currentArticles = 0;
        for (SaleRepository.PromotionSales row : sales.sumPromotionSales(storeId, previousFrom, previousTo,
                currentFrom, currentTo, from, to, PromotionReportService.PRICE_FACTOR,
                PromotionReportService.MIN_REFERENCE_DAYS, codes)) {
            if ("c".equals(row.getPeriod())) {
                current = row.getSaleValueGross();
                currentNet = row.getSaleValueNet();
                currentMargin = row.getMarginValue();
                currentArticles = row.getArticles();
            } else {
                previous = row.getSaleValueGross();
                previousNet = row.getSaleValueNet();
                previousMargin = row.getMarginValue();
                previousArticles = row.getArticles();
            }
        }
        return new SalesPromotionShare(previous, current, previousNet, currentNet, previousMargin, currentMargin,
                previousArticles, currentArticles);
    }

    /** Dzień sprzed wszystkich raportów: okres porównania drugiego sklepu, który niczego nie czyta. */
    private static final LocalDate NO_SALES_DAY = LocalDate.of(1900, 1, 1);

    private static Level level(String code, boolean unassigned) {
        if (unassigned || code.length() == 6) {
            return Level.PRODUCT;
        }
        return code.isEmpty() ? Level.REGION : code.length() == 2 ? Level.FAMILY : Level.SUBFAMILY;
    }

    /**
     * Rozwinięta grupa czyta sprzedaż tylko swoich artykułów; cały sklep i artykuły bez nomenklatury sumują całą
     * sprzedaż okresów.
     */
    private List<SaleRepository.ComparisonRow> query(UUID storeId, LocalDate previousFrom, LocalDate previousTo,
            LocalDate currentFrom, LocalDate currentTo, String code, boolean unassigned, String[] codes) {
        if (unassigned) {
            return sales.compareUnassignedArticles(storeId, previousFrom, previousTo, currentFrom, currentTo, codes);
        }
        if (code.length() == 6) {
            return sales.compareArticles(storeId, previousFrom, previousTo, currentFrom, currentTo, code, codes);
        }
        if (code.isEmpty()) {
            return sales.compareByNomenclature(storeId, previousFrom, previousTo, currentFrom, currentTo, 2, codes);
        }
        return sales.compareGroupByNomenclature(storeId, previousFrom, previousTo, currentFrom, currentTo,
                code.length() + 2, code, codes);
    }

    /**
     * Nasze wiersze z aktualną sprzedażą drugiego sklepu (po kodzie nomenklatury albo ITM8). To, co sprzedał tylko
     * drugi sklep, dochodzi z zerami u nas: pozycje nomenklatury na swoje miejsce wg kodu, artykuły na koniec.
     */
    static List<SalesComparisonRow> withOther(List<SalesComparisonRow> ours, List<SalesComparisonRow> theirs,
            Level level) {
        Map<String, SalesComparisonRow> byCode = new LinkedHashMap<>();
        theirs.forEach(row -> byCode.put(row.code(), row));
        List<SalesComparisonRow> result = new ArrayList<>();
        for (SalesComparisonRow row : ours) {
            SalesComparisonRow other = byCode.remove(row.code());
            result.add(new SalesComparisonRow(row.code(), row.name(), row.ean(), row.brand(), row.previous(),
                    row.current(), other == null ? SalesFigures.ZERO : other.current()));
        }
        byCode.values().forEach(row -> result.add(new SalesComparisonRow(row.code(), row.name(), row.ean(),
                row.brand(), SalesFigures.ZERO, SalesFigures.ZERO, row.current())));
        if (level != Level.PRODUCT) {
            // Pusty kod (sprzedaż bez nomenklatury) zawsze na końcu.
            result.sort(Comparator.comparing((SalesComparisonRow row) -> row.code().isEmpty())
                    .thenComparing(SalesComparisonRow::code));
        }
        return result;
    }

    /** Cała nomenklatura sklepu w kolejności kodów (do filtra raportów). */
    public List<NomenclatureOption> nomenclatures(UUID storeId) {
        return nomenclatures.findAllByStore(storeId).stream().map(nomenclatureMapper::toOption).toList();
    }

    /**
     * Sprzedaż dzień po dniu, każdy dzień okresu (bez sprzedaży = zera): artykułu ({@code itm8}), a bez niego
     * pozycji nomenklatury {@code parent} (pusty = cały sklep) albo, przy {@code unassigned}, sprzedaży bez
     * nomenklatury; {@code filter} jak w porównaniu, żeby wykres grupy zgadzał się z jej wierszem.
     */
    public SalesHistoryResponse history(UUID storeId, LocalDate from, LocalDate to, String itm8, String parent,
            boolean unassigned, List<String> filter) {
        ReportPeriod.check(from, to, ReportPeriod.MAX_DAYS, "Okres wykresu");
        boolean article = itm8 != null && !itm8.isBlank();
        String code = article ? null : parentCode(parent, unassigned);
        String[] codes = article ? null : filterCodes(filter);
        sales.tuneForReport();
        List<SaleRepository.DayRow> found = article
                ? sales.articleHistory(storeId, itm8.strip(), from, to)
                : sales.groupHistory(storeId, code, unassigned, codes, from, to);
        // Likwidacje mają wartość zakupu z minusem (wydanie), strata to jej przeciwność.
        Map<LocalDate, BigDecimal> losses = new HashMap<>();
        (article
                ? documentLines.sumArticleLossesByDay(storeId, itm8.strip(), from, to)
                : documentLines.sumGroupLossesByDay(storeId, code, unassigned, codes, from, to))
                .forEach(day -> losses.put(day.getDay(), day.getPurchaseValue().negate()));

        Map<LocalDate, SalesFigures> byDay = new HashMap<>();
        found.forEach(row -> byDay.put(row.getSaleDate(), new SalesFigures(row.getQuantity(), row.getCostValue(),
                row.getSaleValueGross(), row.getSaleValueNet(), row.getMarginValue())));
        List<SalesHistoryDay> days = new ArrayList<>();
        SalesFigures total = SalesFigures.ZERO;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            SalesFigures figures = byDay.getOrDefault(day, SalesFigures.ZERO);
            days.add(new SalesHistoryDay(day, figures, losses.getOrDefault(day, BigDecimal.ZERO)));
            total = total.plus(figures);
        }
        return new SalesHistoryResponse(from, to, days, total,
                losses.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Artykuł z kartoteki sklepu z rejonem, rodziną i podrodziną; 404, gdy go w kartotece nie ma. */
    public SalesArticleResponse article(UUID storeId, String itm8) {
        Product product = products.findByStoreIdAndItm8(storeId, itm8.strip())
                .orElseThrow(() -> ApiException.notFound("Artykułu " + itm8 + " nie ma w kartotece sklepu"));
        String subfamily = product.getSubfamilyCode();
        List<NomenclatureOption> path = subfamily == null ? List.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId,
                                List.of(subfamily.substring(0, 2), subfamily.substring(0, 4), subfamily))
                        .stream()
                        .map(nomenclatureMapper::toOption)
                        .sorted(Comparator.comparing(NomenclatureOption::code))
                        .toList();
        BigDecimal vatPercent = product.vatPercent();
        if (vatPercent == null) {
            Short rate = documentLines.findLatestVatRate(storeId, product.getItm8());
            vatPercent = rate == null ? null : BigDecimal.valueOf(rate);
        }
        List<SalesArticleResponse.Merged> merged = merges.findAliases(storeId, product.getItm8()).stream()
                .map(merge -> merged(storeId, merge.getAliasItm8(), merge.getEan()))
                .toList();
        SalesArticleResponse.Merged mergedInto = merges.findMain(storeId, product.getItm8())
                .map(merge -> merged(storeId, merge.getMainItm8(), merge.getEan()))
                .orElse(null);
        boolean inCadencier = cadencier.isInCadencier(storeId, product.getItm8());
        CadPrices.Price cadencierCurrent = !inCadencier ? null
                : cadPrices.current(storeId, List.of(product.getItm8())).of(product.getItm8());
        return salesMapper.toArticle(product, path, vatPercent, inCadencier, cadencierCurrent, merged, mergedInto);
    }

    private SalesArticleResponse.Merged merged(UUID storeId, String itm8, String ean) {
        return new SalesArticleResponse.Merged(itm8,
                products.findByStoreIdAndItm8(storeId, itm8).map(Product::getName).orElse(null), ean);
    }

    /** Ostatnie dostawy artykułu (przyjęcia PZ), od najnowszej, najwyżej {@link #DELIVERY_LIMIT}. */
    public List<ArticleDeliveryResponse> deliveries(UUID storeId, String itm8) {
        return deliveryPage(storeId, itm8, null, null, true, 0, DELIVERY_LIMIT).items();
    }

    /**
     * Strona dostaw artykułu w karcie: {@code limit} od {@code offset} (najwyżej {@link #PAGE_LIMIT}), posortowana po
     * {@code sort} ({@link #DELIVERY_SORTS}, domyślnie data), z wyszukiwaniem po numerze, dostawcy i nr dok. dostawcy
     * (każde słowo). Zmiana ceny liczy się zawsze względem poprzedniej dostawy w czasie.
     */
    public ArticleRowsPage<ArticleDeliveryResponse> deliveryPage(UUID storeId, String itm8, String query, String sort,
            boolean descending, int offset, int limit) {
        List<DocumentLineRepository.ArticleDelivery> deliveries = documentLines.findArticleDeliveries(storeId,
                itm8.strip(), DataEntryService.likePatterns(query, true), sortOf(sort, DELIVERY_SORTS), descending,
                Math.max(offset, 0), pageLimit(limit));
        // Nazwa i okres gazetki przy kodzie MEA (z formularzy pobranych z portalu).
        Set<String> codes = deliveries.stream().map(DocumentLineRepository.ArticleDelivery::getMeaCodes)
                .filter(Objects::nonNull).flatMap(value -> Arrays.stream(value.split(",")))
                .collect(Collectors.toSet());
        Map<String, MeaDocument> mea = codes.isEmpty() ? Map.of()
                : meaDocuments.findByNumberIn(codes).stream()
                        .collect(Collectors.toMap(MeaDocument::getNumber, Function.identity()));
        return new ArticleRowsPage<>(deliveries.stream().map(delivery -> salesMapper.toDelivery(delivery, mea)).toList(),
                deliveries.isEmpty() ? 0 : deliveries.getFirst().getTotal());
    }

    /** Ostatnie protokoły artykułu (bez przyjęć i korekt), od najnowszego, najwyżej {@link #PROTOCOL_LIMIT}. */
    public List<ArticleProtocolResponse> protocols(UUID storeId, String itm8) {
        return protocolPage(storeId, itm8, null, null, true, 0, PROTOCOL_LIMIT).items();
    }

    /**
     * Strona protokołów artykułu w karcie, jak {@link #deliveryPage}. Wyszukiwanie: numer zawiera każde słowo albo
     * całe zapytanie jest fragmentem nazwy rodzaju („likw” = likwidacje, „gratis” = przeniesienia jako gratis).
     */
    public ArticleRowsPage<ArticleProtocolResponse> protocolPage(UUID storeId, String itm8, String query, String sort,
            boolean descending, int offset, int limit) {
        String[] patterns = DataEntryService.likePatterns(query, true);
        String needle = patterns.length == 0 ? null : plain(query);
        String[] types = needle == null ? new String[0]
                : Arrays.stream(DocumentType.values())
                        .filter(type -> plain(type.label()).contains(needle) || plain(type.singular()).contains(needle))
                        .map(Enum::name).toArray(String[]::new);
        boolean gratis = needle != null && "GRATIS".contains(needle);
        List<DocumentLineRepository.ArticleProtocol> protocols = documentLines.findArticleProtocols(storeId,
                itm8.strip(), patterns, types, gratis, sortOf(sort, PROTOCOL_SORTS), descending, Math.max(offset, 0),
                pageLimit(limit));
        return new ArticleRowsPage<>(protocols.stream().map(salesMapper::toProtocol).toList(),
                protocols.isEmpty() ? 0 : protocols.getFirst().getTotal());
    }

    private static final Set<String> DELIVERY_SORTS = Set.of("date", "number", "supplier", "quantity", "price", "value");
    private static final Set<String> PROTOCOL_SORTS = Set.of("date", "type", "number", "quantity");

    /** Najwięcej wierszy na stronę karty artykułu. */
    static final int PAGE_LIMIT = 100;

    private static String sortOf(String sort, Set<String> allowed) {
        return sort != null && allowed.contains(sort) ? sort : "date";
    }

    private static int pageLimit(int limit) {
        return Math.clamp(limit, 1, PAGE_LIMIT);
    }

    /** Wielkie litery bez polskich znaków, jak wzorce z {@link DataEntryService#likePatterns}. */
    private static String plain(String text) {
        return java.text.Normalizer.normalize(text.strip().toUpperCase(java.util.Locale.ROOT).replace('Ł', 'L'),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static String parentCode(String parent, boolean unassigned) {
        String code = parent == null ? "" : parent.strip();
        if (!PARENT.matcher(code).matches() || unassigned && !code.isEmpty()) {
            throw ApiException.badRequest("Nieznana pozycja nomenklatury: " + code);
        }
        return code;
    }

    private static String[] filterCodes(List<String> filter) {
        if (filter == null) {
            return new String[0];
        }
        List<String> codes = filter.stream().map(String::strip).filter(code -> !code.isEmpty()).distinct().toList();
        for (String code : codes) {
            if (!FILTER_CODE.matcher(code).matches()) {
                throw ApiException.badRequest("Nieznana pozycja nomenklatury: " + code);
            }
        }
        return codes.toArray(String[]::new);
    }

    private static SalesComparisonRow toRow(SaleRepository.ComparisonRow row) {
        return new SalesComparisonRow(
                row.getCode() == null ? "" : row.getCode(),
                row.getName(),
                row.getEan(),
                row.getBrand(),
                new SalesFigures(row.getPreviousQuantity(), row.getPreviousCostValue(),
                        row.getPreviousSaleValueGross(), row.getPreviousSaleValueNet(),
                        row.getPreviousMarginValue()),
                new SalesFigures(row.getCurrentQuantity(), row.getCurrentCostValue(),
                        row.getCurrentSaleValueGross(), row.getCurrentSaleValueNet(),
                        row.getCurrentMarginValue()));
    }
}
