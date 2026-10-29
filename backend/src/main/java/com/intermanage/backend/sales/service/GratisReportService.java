package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.GratisKind;
import com.intermanage.backend.document.repository.DocumentGratisRepository;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.sales.component.GratisReportPdf;
import com.intermanage.backend.sales.dto.GratisReportResponse;
import com.intermanage.backend.sales.dto.GratisReportResponse.Figures;
import com.intermanage.backend.sales.dto.GratisReportResponse.Region;
import com.intermanage.backend.sales.dto.GratisReportResponse.Tile;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Raport przyjętych gratisów: przeniesienia stanów zarejestrowane jako gratis, po miesiącu gratisu (wybranym przy
 * rejestracji albo z daty dokumentu) i rejonie nomenklatury linii, osobno za handel / ekspozycję i za pokrycie strat.
 * Wartością jest sprzedaż brutto linii, a obok sprzedaż brutto rejonu w tym miesiącu i udział gratisu w niej.
 */
@Service
@Transactional(readOnly = true)
public class GratisReportService {

    /** Gratisy rejestrujemy od 2026 roku; wcześniejszych miesięcy raport nie pokazuje. */
    public static final YearMonth FIRST_MONTH = YearMonth.of(2026, 1);

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.of("pl", "PL");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String UNASSIGNED_NAME = "Bez nomenklatury";

    private final DocumentGratisRepository gratis;
    private final SaleRepository sales;
    private final DocumentImportRepository documentImports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PeriodSummaryService periodSummary;
    private final GratisReportPdf reportPdf;

    public GratisReportService(DocumentGratisRepository gratis, SaleRepository sales,
            DocumentImportRepository documentImports, StoreRepository stores, AppUserRepository users,
            PeriodSummaryService periodSummary, GratisReportPdf reportPdf) {
        this.gratis = gratis;
        this.sales = sales;
        this.documentImports = documentImports;
        this.stores = stores;
        this.users = users;
        this.periodSummary = periodSummary;
        this.reportPdf = reportPdf;
    }

    /** Kafelki miesięcy od {@code from} do {@code to}; przy kilku miesiącach także podsumowanie okresu. */
    public GratisReportResponse report(UUID storeId, YearMonth from, YearMonth to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("Miesiąc początkowy jest późniejszy niż końcowy");
        }
        if (from.isBefore(FIRST_MONTH)) {
            throw ApiException.badRequest("Raport obejmuje miesiące od stycznia 2026");
        }
        if (to.isAfter(YearMonth.now(STORE_ZONE))) {
            throw ApiException.badRequest("Raport nie może sięgać w przyszłe miesiące");
        }
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        LocalDate first = from.atDay(1);
        LocalDate last = to.atEndOfMonth();

        Map<YearMonth, Map<String, Sums>> byMonth = new HashMap<>();
        gratis.sumByMonthAndRegion(storeId, first, to.atDay(1)).forEach(row -> {
            BigDecimal value = row.getSaleValueGross();
            Sums sums = GratisKind.valueOf(row.getKind()) == GratisKind.LOSSES
                    ? new Sums(BigDecimal.ZERO, value, BigDecimal.ZERO)
                    : new Sums(value, BigDecimal.ZERO, BigDecimal.ZERO);
            byMonth.computeIfAbsent(YearMonth.from(row.getMonth()), month -> new HashMap<>())
                    .merge(row.getRegionCode(), sums, Sums::plus);
        });
        Map<YearMonth, Map<String, BigDecimal>> salesByMonth = new HashMap<>();
        sales.sumGrossByMonthAndRegion(storeId, first, last).forEach(row -> salesByMonth
                .computeIfAbsent(YearMonth.from(row.getMonth()), month -> new HashMap<>())
                .merge(row.getRegionCode(), row.getSaleValueGross(), BigDecimal::add));
        Map<YearMonth, Long> documentsByMonth = new HashMap<>();
        gratis.countByMonth(storeId, first, to.atDay(1))
                .forEach(row -> documentsByMonth.put(YearMonth.from(row.getMonth()), row.getDocumentCount()));

        Map<String, String> names = periodSummary.regionNames(storeId);
        List<Tile> months = new ArrayList<>();
        Map<String, Sums> periodRegions = new HashMap<>();
        Map<String, BigDecimal> periodSales = new HashMap<>();
        long periodDocuments = 0;
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            Map<String, Sums> regions = byMonth.getOrDefault(month, Map.of());
            Map<String, BigDecimal> regionSales = salesByMonth.getOrDefault(month, Map.of());
            long documents = documentsByMonth.getOrDefault(month, 0L);
            months.add(tile(month, month, monthLabel(month), documents, regions, regionSales, names));
            regions.forEach((code, sums) -> periodRegions.merge(code, sums, Sums::plus));
            regionSales.forEach((code, value) -> periodSales.merge(code, value, BigDecimal::add));
            periodDocuments += documents;
        }
        Tile summary = from.equals(to) ? null
                : tile(from, to, periodLabel(from, to), periodDocuments, periodRegions, periodSales, names);

        return new GratisReportResponse(store.getNumber(), store.getName(), from, to,
                sales.findCoverage(storeId).getLastDate(), documentImports.findLastPeriodTo(storeId).orElse(null),
                months, summary);
    }

    /** Wydruk raportu (PDF, A4 pionowo, kafelki po 4 albo 6 na stronie). */
    public ExportFile pdf(UUID storeId, YearMonth from, YearMonth to, UUID userId) {
        GratisReportResponse report = report(storeId, from, to);
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        return new ExportFile("gratisy-" + report.storeNumber() + "-" + from + (from.equals(to) ? "" : "-" + to)
                + ".pdf", "application/pdf", reportPdf.pdf(report, printedBy));
    }

    /** Kafelek: rejony z gratisem (kodami, bez nomenklatury na końcu) i suma ze sprzedażą całego sklepu. */
    private static Tile tile(YearMonth from, YearMonth to, String label, long documents, Map<String, Sums> regions,
            Map<String, BigDecimal> regionSales, Map<String, String> names) {
        List<Region> rows = regions.entrySet().stream()
                .filter(entry -> !entry.getValue().empty())
                .sorted(Map.Entry.<String, Sums>comparingByKey(
                        Comparator.comparing(String::isEmpty).thenComparing(Comparator.naturalOrder())))
                .map(entry -> new Region(entry.getKey(),
                        entry.getKey().isEmpty() ? UNASSIGNED_NAME : names.get(entry.getKey()),
                        figures(entry.getValue().withSales(regionSales.getOrDefault(entry.getKey(), BigDecimal.ZERO)))))
                .toList();
        Sums total = regions.values().stream().reduce(Sums.ZERO, Sums::plus)
                .withSales(regionSales.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        return new Tile(from, to, label, documents, rows, figures(total));
    }

    private static Figures figures(Sums sums) {
        BigDecimal gratisValue = sums.trade().add(sums.loss());
        return new Figures(sums.trade(), sums.loss(), gratisValue, sums.sales(),
                sums.sales().signum() == 0 ? null
                        : gratisValue.multiply(HUNDRED).divide(sums.sales(), 2, RoundingMode.HALF_UP));
    }

    /** "Październik 2026" */
    static String monthLabel(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, POLISH);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + month.getYear();
    }

    /** "Rok 2026" dla pełnego roku albo jego początku do bieżącego miesiąca, inaczej "Styczeń – marzec 2026". */
    static String periodLabel(YearMonth from, YearMonth to) {
        if (from.getYear() == to.getYear() && from.getMonthValue() == 1
                && (to.getMonthValue() == 12 || to.equals(YearMonth.now(STORE_ZONE)))) {
            return "Rok " + from.getYear();
        }
        String start = monthLabel(from);
        String end = to.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, POLISH) + " " + to.getYear();
        return (from.getYear() == to.getYear() ? start.substring(0, start.length() - 5) : start) + " – " + end;
    }

    private record Sums(BigDecimal trade, BigDecimal loss, BigDecimal sales) {

        static final Sums ZERO = new Sums(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        Sums plus(Sums other) {
            return new Sums(trade.add(other.trade), loss.add(other.loss), sales.add(other.sales));
        }

        Sums withSales(BigDecimal value) {
            return new Sums(trade, loss, value);
        }

        boolean empty() {
            return trade.signum() == 0 && loss.signum() == 0;
        }
    }
}
