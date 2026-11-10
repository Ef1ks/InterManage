package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.dto.NomenclatureOption;
import com.intermanage.backend.nomenclature.mapper.NomenclatureMapper;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.sales.dto.ArticleDeliveryResponse;
import com.intermanage.backend.sales.dto.ArticleProtocolResponse;
import com.intermanage.backend.sales.dto.SalesArticleResponse;
import com.intermanage.backend.sales.dto.SalesComparisonResponse.Level;
import com.intermanage.backend.sales.dto.SalesComparisonResponse;
import com.intermanage.backend.sales.dto.SalesComparisonRow;
import com.intermanage.backend.sales.dto.SalesFigures;
import com.intermanage.backend.sales.dto.SalesHistoryDay;
import com.intermanage.backend.sales.dto.SalesHistoryResponse;
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

    public SalesReportService(SaleRepository sales, SalesImportRepository imports, ProductRepository products,
            NomenclatureRepository nomenclatures, SalesMapper salesMapper, NomenclatureMapper nomenclatureMapper,
            DocumentLineRepository documentLines, CadencierImportRepository cadencier,
            CounterReportService counterReports) {
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
        SalesFigures previousTotal = rows.stream().map(SalesComparisonRow::previous)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        SalesFigures currentTotal = rows.stream().map(SalesComparisonRow::current)
                .reduce(SalesFigures.ZERO, SalesFigures::plus);
        return new SalesComparisonResponse(previousFrom, previousTo, currentFrom, currentTo,
                unassigned ? null : code, level, rows, previousTotal, currentTotal, otherTotal);
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
        return salesMapper.toArticle(product, path, vatPercent,
                cadencier.isInCadencier(storeId, product.getItm8()));
    }

    /** Ostatnie dostawy artykułu (przyjęcia PZ), od najnowszej, najwyżej {@link #DELIVERY_LIMIT}. */
    public List<ArticleDeliveryResponse> deliveries(UUID storeId, String itm8) {
        return documentLines.findArticleDeliveries(storeId, itm8.strip(), DELIVERY_LIMIT).stream()
                .map(salesMapper::toDelivery)
                .toList();
    }

    /** Ostatnie protokoły artykułu (bez przyjęć i korekt), od najnowszego, najwyżej {@link #PROTOCOL_LIMIT}. */
    public List<ArticleProtocolResponse> protocols(UUID storeId, String itm8) {
        return documentLines.findArticleProtocols(storeId, itm8.strip(), PROTOCOL_LIMIT).stream()
                .map(salesMapper::toProtocol)
                .toList();
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
