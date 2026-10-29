package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.component.PeriodSummaryPdf;
import com.intermanage.backend.sales.component.RegionGroups;
import com.intermanage.backend.sales.component.RegionGroups.Group;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Figures;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Kind;
import com.intermanage.backend.sales.dto.PeriodSummaryResponse.Line;
import com.intermanage.backend.sales.dto.RegionBudgetResponse;
import com.intermanage.backend.sales.dto.RegionBudgetsRequest;
import com.intermanage.backend.sales.entity.RegionBudget;
import com.intermanage.backend.sales.mapper.SalesMapper;
import com.intermanage.backend.sales.repository.RegionBudgetRepository;
import com.intermanage.backend.sales.repository.SaleRepository;
import com.intermanage.backend.sales.repository.SalesImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Podsumowanie sprzedaży i strat za okres (następca arkusza "kombajn"): sprzedaż każdego rejonu nomenklatury
 * z zapisanej sprzedaży, straty z likwidacji (PL) z linii dokumentów wg nomenklatury linii, marża po stratach oraz budżet rejonu (docelowa marża i najwyższe straty) z ustawień sklepu.
 */
@Service
@Transactional(readOnly = true)
public class PeriodSummaryService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String UNASSIGNED_NAME = "Bez nomenklatury";

    private final SaleRepository sales;
    private final SalesImportRepository salesImports;
    private final DocumentLineRepository documentLines;
    private final DocumentImportRepository documentImports;
    private final NomenclatureRepository nomenclatures;
    private final RegionBudgetRepository budgets;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final SalesMapper salesMapper;
    private final PeriodSummaryPdf summaryPdf;

    public PeriodSummaryService(SaleRepository sales, SalesImportRepository salesImports,
            DocumentLineRepository documentLines, DocumentImportRepository documentImports,
            NomenclatureRepository nomenclatures, RegionBudgetRepository budgets, StoreRepository stores,
            AppUserRepository users, SalesMapper salesMapper, PeriodSummaryPdf summaryPdf) {
        this.salesMapper = salesMapper;
        this.summaryPdf = summaryPdf;
        this.sales = sales;
        this.salesImports = salesImports;
        this.documentLines = documentLines;
        this.documentImports = documentImports;
        this.nomenclatures = nomenclatures;
        this.budgets = budgets;
        this.stores = stores;
        this.users = users;
    }

    /** Wiersze w kolejności wydruku; rejony bez sprzedaży i strat (i puste grupy) są pomijane. */
    public PeriodSummaryResponse summary(UUID storeId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("Data początkowa jest późniejsza niż końcowa");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > SalesReportService.MAX_PERIOD_DAYS) {
            throw ApiException.badRequest("Okres może mieć najwyżej " + SalesReportService.MAX_PERIOD_DAYS + " dni");
        }
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));

        Map<String, Sums> byRegion = new HashMap<>();
        sales.sumByRegion(storeId, from, to).forEach(row -> byRegion.merge(row.getRegionCode(),
                new Sums(row.getSaleValueNet(), row.getSaleValueGross(), row.getCostValue(), row.getMarginValue(),
                        BigDecimal.ZERO), Sums::plus));
        // Likwidacje to wydania z magazynu, z wartością z minusem; strata to jej przeciwność.
        documentLines.sumByRegion(storeId, List.of(DocumentType.PL.name()), from, to)
                .forEach(row -> byRegion.merge(row.getRegionCode(), new Sums(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, row.getPurchaseValue().negate()), Sums::plus));
        byRegion.values().removeIf(Sums::empty);

        Map<String, String> names = regionNames(storeId);
        Map<String, RegionBudget> budgetByRegion = new HashMap<>();
        budgets.findByStoreIdOrderByRegionCode(storeId)
                .forEach(budget -> budgetByRegion.put(budget.getRegionCode(), budget));

        Sums total = byRegion.values().stream().reduce(Sums.ZERO, Sums::plus);
        Builder builder = new Builder(byRegion, names, budgetByRegion, total.saleGross());
        RegionGroups.GROUPS.forEach(group -> builder.group(group, 0));

        return new PeriodSummaryResponse(store.getNumber(), store.getName(), from, to,
                sales.findCoverage(storeId).getLastDate(), documentImports.findLastPeriodTo(storeId).orElse(null),
                salesImports.findLastReportGeneratedAt(storeId).orElse(null),
                documentImports.findNewestReportGeneratedAt(storeId).orElse(null),
                budgetByRegion.values().stream()
                        .anyMatch(budget -> budget.getMarginPercent() != null || budget.getMaxLossPercent() != null),
                builder.lines, builder.figures(total));
    }

    /** Wydruk podsumowania (PDF, A4 poziomo). */
    public ExportFile pdf(UUID storeId, LocalDate from, LocalDate to, UUID userId) {
        PeriodSummaryResponse summary = summary(storeId, from, to);
        String printedBy = userId != null ? users.findById(userId).map(AppUser::getDisplayName).orElse(null) : null;
        return new ExportFile("podsumowanie-" + summary.storeNumber() + "-" + from + "-" + to + ".pdf",
                "application/pdf", summaryPdf.pdf(summary, printedBy));
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    /** Budżety wszystkich rejonów: z grup podsumowania i z nomenklatury sklepu, w kolejności wydruku. */
    public List<RegionBudgetResponse> budgets(UUID storeId) {
        Map<String, String> names = regionNames(storeId);
        Map<String, RegionBudget> saved = new HashMap<>();
        budgets.findByStoreIdOrderByRegionCode(storeId).forEach(budget -> saved.put(budget.getRegionCode(), budget));
        Set<String> codes = new HashSet<>(RegionGroups.listedRegions());
        codes.addAll(names.keySet());
        codes.addAll(saved.keySet());
        List<String> order = RegionGroups.listedRegions();
        List<Group> leaves = RegionGroups.GROUPS.stream().flatMap(Group::leaves).toList();
        return codes.stream()
                .sorted(Comparator.<String>comparingInt(code -> leaves.indexOf(RegionGroups.leafOf(code)))
                        .thenComparingInt(code -> order.contains(code) ? order.indexOf(code) : Integer.MAX_VALUE)
                        .thenComparing(Comparator.naturalOrder()))
                .map(code -> salesMapper.toBudget(code, names.get(code), RegionGroups.leafOf(code).name(),
                        saved.get(code)))
                .toList();
    }

    /** Zapisuje budżety podanych rejonów (pusta wartość czyści); pozostałe zostają bez zmian. */
    @Transactional
    public List<RegionBudgetResponse> saveBudgets(UUID storeId, RegionBudgetsRequest request, UUID userId) {
        Map<String, RegionBudget> saved = new HashMap<>();
        budgets.findByStoreIdOrderByRegionCode(storeId).forEach(budget -> saved.put(budget.getRegionCode(), budget));
        Store store = stores.getReferenceById(storeId);
        for (RegionBudgetsRequest.Item item : request.budgets()) {
            RegionBudget budget = saved.get(item.regionCode());
            if (budget == null) {
                if (item.marginPercent() == null && item.maxLossPercent() == null) {
                    continue;
                }
                budget = new RegionBudget(store, item.regionCode());
                saved.put(item.regionCode(), budget);
            }
            if (budget.update(item.marginPercent(), item.maxLossPercent(),
                    userId != null ? users.getReferenceById(userId) : null)) {
                budgets.save(budget);
            }
        }
        budgets.flush();
        return budgets(storeId);
    }

    /** Nazwy rejonów z nomenklatury sklepu, bez kodu z przodu ("14_OWOCE/WARZYWA" → "OWOCE/WARZYWA"). */
    public Map<String, String> regionNames(UUID storeId) {
        Map<String, String> names = new HashMap<>();
        for (Nomenclature region : nomenclatures.findByStoreIdAndLevelOrderByCode(storeId, NomenclatureLevel.REGION)) {
            names.put(region.getCode(), region.getName().replaceFirst("^\\d+_", "").strip());
        }
        return names;
    }

    /** Składa wiersze: nagłówek grupy, jej rejony albo podgrupy i sumę grupy. */
    private static final class Builder {

        private final Map<String, Sums> byRegion;
        private final Map<String, String> names;
        private final Map<String, RegionBudget> budgets;
        private final BigDecimal storeGross;
        private final List<String> listed = RegionGroups.listedRegions();
        private final List<Line> lines = new ArrayList<>();

        Builder(Map<String, Sums> byRegion, Map<String, String> names, Map<String, RegionBudget> budgets,
                BigDecimal storeGross) {
            this.byRegion = byRegion;
            this.names = names;
            this.budgets = budgets;
            this.storeGross = storeGross;
        }

        /** Dopisuje grupę i zwraca jej sumę; grupa bez sprzedaży i strat nie ma wierszy. */
        Sums group(Group group, int depth) {
            List<String> regions = group.leaf() ? regionsOf(group) : List.of();
            if (group.leaf() && regions.isEmpty()) {
                return Sums.ZERO;
            }
            int header = lines.size();
            lines.add(new Line(Kind.GROUP, depth, null, group.name(), null, null, null));
            Sums sum = Sums.ZERO;
            if (group.leaf()) {
                for (String code : regions) {
                    Sums region = byRegion.get(code);
                    RegionBudget budget = budgets.get(code);
                    lines.add(new Line(Kind.REGION, depth + 1, code,
                            code.isEmpty() ? UNASSIGNED_NAME : names.get(code), figures(region),
                            budget != null ? budget.getMarginPercent() : null,
                            budget != null ? budget.getMaxLossPercent() : null));
                    sum = sum.plus(region);
                }
            } else {
                for (Group child : group.children()) {
                    sum = sum.plus(group(child, depth + 1));
                }
                if (lines.size() == header + 1) {
                    lines.removeLast();
                    return Sums.ZERO;
                }
            }
            lines.add(new Line(Kind.SUBTOTAL, depth, null, "Razem " + group.name().toLowerCase(), figures(sum),
                    null, null));
            return sum;
        }

        /** Rejony grupy, które mają sprzedaż albo straty: najpierw w kolejności z zestawienia, potem reszta kodami. */
        private List<String> regionsOf(Group group) {
            List<String> regions = new ArrayList<>(group.regions().stream().filter(byRegion::containsKey).toList());
            if (group.rest()) {
                byRegion.keySet().stream()
                        .filter(code -> !listed.contains(code))
                        .sorted(Comparator.comparing(String::isEmpty).thenComparing(Comparator.naturalOrder()))
                        .forEach(regions::add);
            }
            return regions;
        }

        Figures figures(Sums sums) {
            BigDecimal afterLosses = sums.margin().subtract(sums.loss());
            return new Figures(sums.saleNet(), sums.saleGross(), sums.cost(), sums.margin(),
                    percent(sums.margin(), sums.saleNet()), sums.loss(), percent(sums.loss(), sums.saleGross()),
                    afterLosses, percent(afterLosses, sums.saleNet()),
                    percent(sums.saleGross(), storeGross));
        }

        private static BigDecimal percent(BigDecimal value, BigDecimal base) {
            return base.signum() == 0 ? null : value.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
        }
    }

    private record Sums(BigDecimal saleNet, BigDecimal saleGross, BigDecimal cost, BigDecimal margin,
            BigDecimal loss) {

        static final Sums ZERO = new Sums(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);

        Sums plus(Sums other) {
            return new Sums(saleNet.add(other.saleNet), saleGross.add(other.saleGross), cost.add(other.cost),
                    margin.add(other.margin), loss.add(other.loss));
        }

        boolean empty() {
            return saleNet.signum() == 0 && saleGross.signum() == 0 && cost.signum() == 0 && margin.signum() == 0
                    && loss.signum() == 0;
        }
    }
}
