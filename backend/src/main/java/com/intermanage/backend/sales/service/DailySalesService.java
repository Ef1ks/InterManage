package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.pricecheck.dto.PriceCheckCategoryResponse;
import com.intermanage.backend.pricecheck.service.PriceCheckCategoryService;
import com.intermanage.backend.sales.component.DailySalesPdf;
import com.intermanage.backend.sales.dto.DailySalesReport;
import com.intermanage.backend.sales.dto.DailySalesReport.Figures;
import com.intermanage.backend.sales.dto.DailySalesReport.Line;
import com.intermanage.backend.sales.dto.DailySalesRequest;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SaleRepository.DayFamilySales;
import com.intermanage.backend.sales.service.PeriodSummaryService.ExportFile;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Sprzedaż dzień po dniu po nomenklaturze (rejony, pod nimi rodziny), na dole suma sklepu w każdym dniu. Okres do
 * {@link #MAX_DAYS} dni, bo każdy dzień to kolumna. Kod pusty = sprzedaż bez nomenklatury, na końcu.
 */
@Service
public class DailySalesService {

    /** Dwa miesiące: więcej kolumn dni nie da się czytać. */
    public static final int MAX_DAYS = 62;
    private static final String UNASSIGNED = "";
    /** Kod kategorii „Pozostałe” (rejony spoza własnych kategorii). */
    public static final String OTHER_CATEGORY = "pozostale";

    private final SaleRepository sales;
    private final NomenclatureRepository nomenclatures;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final DailySalesPdf reportPdf;
    private final PriceCheckCategoryService categories;

    public DailySalesService(SaleRepository sales, NomenclatureRepository nomenclatures, StoreRepository stores,
            AppUserRepository users, DailySalesPdf reportPdf, PriceCheckCategoryService categories) {
        this.categories = categories;
        this.sales = sales;
        this.nomenclatures = nomenclatures;
        this.stores = stores;
        this.users = users;
        this.reportPdf = reportPdf;
    }

    @Transactional(readOnly = true)
    public DailySalesReport report(UUID storeId, DailySalesRequest request) {
        if (request.from() == null || request.to() == null) {
            throw ApiException.badRequest("Podaj okres");
        }
        ReportPeriod.check(request.from(), request.to(), MAX_DAYS);
        sales.tuneForReport();
        List<LocalDate> days = request.from().datesUntil(request.to().plusDays(1)).toList();
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int index = 0; index < days.size(); index++) {
            dayIndex.put(days.get(index), index);
        }
        Map<String, String> names = nomenclatures.findAllByStore(storeId).stream()
                .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (first, second) -> first));

        // rodzina → dzień → sumy
        Map<String, Figures[]> families = new TreeMap<>(CODE_ORDER);
        LocalDate lastSale = null;
        for (DayFamilySales row : sales.sumByDayAndFamily(storeId, request.from(), request.to())) {
            Integer index = dayIndex.get(row.getSaleDate());
            if (index == null) {
                continue;
            }
            String code = row.getFamilyCode() == null || row.getFamilyCode().length() < 2 ? UNASSIGNED
                    : row.getFamilyCode();
            Figures[] cells = families.computeIfAbsent(code, key -> new Figures[days.size()]);
            cells[index] = plus(cells[index], new Figures(row.getQuantity(), row.getSaleValueGross(),
                    row.getSaleValueNet(), row.getMarginValue()));
            if (lastSale == null || row.getSaleDate().isAfter(lastSale)) {
                lastSale = row.getSaleDate();
            }
        }

        Map<String, List<Line>> byRegion = new TreeMap<>(CODE_ORDER);
        families.forEach((code, cells) -> {
            String region = code.isEmpty() ? UNASSIGNED : code.substring(0, 2);
            if (code.isEmpty() || code.length() == 2) {
                byRegion.computeIfAbsent(region, key -> new ArrayList<>());
                byRegion.get(region).add(line(code, null, "FAMILY", Arrays.asList(cells), List.of()));
                return;
            }
            byRegion.computeIfAbsent(region, key -> new ArrayList<>())
                    .add(line(code, names.get(code), "FAMILY", Arrays.asList(cells), List.of()));
        });
        List<Line> regions = new ArrayList<>();
        byRegion.forEach((region, children) -> {
            List<Figures> cells = sumDays(children, days.size());
            boolean plain = region.isEmpty() || children.stream().allMatch(child -> child.code().length() == 2);
            regions.add(line(region, region.isEmpty() ? null : names.get(region), "REGION", cells,
                    plain ? List.of() : children));
        });
        Line total = line(UNASSIGNED, null, "TOTAL", sumDays(regions, days.size()), List.of());
        List<Line> top = request.grouping() == DailySalesRequest.Grouping.CATEGORY
                ? byCategory(storeId, regions, days.size()) : regions;
        return new DailySalesReport(request.from(), request.to(), days, lastSale, total, top);
    }

    /**
     * Rejony zebrane we własne kategorie sklepu (Ustawienia sklepu → Weryfikacja cen, rejony nomenklatury): kategoria
     * sumuje swoje rejony, a „Pozostałe” rejony spoza kategorii i sprzedaż bez nomenklatury. Rejon może należeć do
     * kilku kategorii, wtedy liczy się w każdej. Pod kategorią jej rejony (bez rodzin). Kategoria bez sprzedaży w
     * okresie jest pomijana.
     */
    private List<Line> byCategory(UUID storeId, List<Line> regions, int size) {
        List<Line> plainRegions = regions.stream()
                .map(region -> new Line(region.code(), region.name(), region.level(), region.days(), region.sum(),
                        List.of()))
                .toList();
        List<Line> result = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (PriceCheckCategoryResponse category : categories.list(storeId)) {
            Set<String> codes = new HashSet<>(category.regionCodes());
            List<Line> members = plainRegions.stream().filter(region -> codes.contains(region.code())).toList();
            assigned.addAll(codes);
            if (!members.isEmpty()) {
                result.add(line(category.id().toString(), category.name(), "CATEGORY", sumDays(members, size),
                        members));
            }
        }
        List<Line> rest = plainRegions.stream().filter(region -> !assigned.contains(region.code())).toList();
        if (!rest.isEmpty()) {
            result.add(line(OTHER_CATEGORY, "Pozostałe", "CATEGORY", sumDays(rest, size), rest));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ExportFile pdf(UUID storeId, DailySalesRequest request, UUID userId) {
        DailySalesReport report = report(storeId, request);
        if (report.regions().isEmpty()) {
            throw ApiException.badRequest("Brak sprzedaży do wydruku");
        }
        Store store = stores.findById(storeId).orElseThrow();
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        DailySalesRequest.Level level = request.level() == null ? DailySalesRequest.Level.REGION : request.level();
        DailySalesRequest.Measure measure = request.measure() == null ? DailySalesRequest.Measure.GROSS
                : request.measure();
        DailySalesRequest.Layout layout = request.layout() == null ? DailySalesRequest.Layout.DAYS_AS_ROWS
                : request.layout();
        byte[] content = reportPdf.pdf(store.getNumber(), store.getName(), report, level, measure, layout,
                request.grouping() == DailySalesRequest.Grouping.CATEGORY, printedBy);
        return new ExportFile("sprzedaz-dzienna-" + store.getNumber() + "-" + request.from() + "-" + request.to()
                + ".pdf", "application/pdf", content);
    }

    private static Line line(String code, String name, String level, List<Figures> days, List<Line> children) {
        Figures sum = null;
        for (Figures day : days) {
            sum = plus(sum, day);
        }
        return new Line(code, name, level, days, sum, children);
    }

    private static List<Figures> sumDays(List<Line> lines, int size) {
        List<Figures> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Figures sum = null;
            for (Line line : lines) {
                sum = plus(sum, line.days().get(index));
            }
            result.add(sum);
        }
        return result;
    }

    private static Figures plus(Figures first, Figures second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new Figures(add(first, second, Figures::quantity), add(first, second, Figures::saleValueGross),
                add(first, second, Figures::saleValueNet), add(first, second, Figures::marginValue));
    }

    private static BigDecimal add(Figures first, Figures second, Function<Figures, BigDecimal> value) {
        BigDecimal left = value.apply(first);
        BigDecimal right = value.apply(second);
        return left == null ? right : right == null ? left : left.add(right);
    }

    /** Kody rosnąco, pusty („Bez nomenklatury”) na końcu. */
    private static final Comparator<String> CODE_ORDER =
            Comparator.comparing(String::isEmpty).thenComparing(Comparator.naturalOrder());
}
