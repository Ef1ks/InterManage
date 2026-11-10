package com.intermanage.backend.sales.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentImportRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository.SupplierTurnoverAmount;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.sales.dto.SupplierTurnoverReport;
import com.intermanage.backend.sales.dto.SupplierTurnoverReport.Amount;
import com.intermanage.backend.sales.dto.SupplierTurnoverReport.Row;
import com.intermanage.backend.sales.dto.TurnoverSupplier;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Obroty z dostawcami: za ile netto sklep kupił od dostawcy w każdym miesiącu okresu, po nomenklaturze linii. Liczą
 * się linie dostawcy w przyjęciach PZ (wg daty przyjęcia) i korekty przypięte do tych przyjęć, w miesiącu przyjęcia,
 * jak przyjęcie po korektach w raporcie przyjęć. Korekty nieprzypięte są tylko podane obok.
 */
@Service
public class SupplierTurnoverService {

    private static final Amount ZERO = new Amount(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    private static final List<NomenclatureLevel> LEVELS = List.of(NomenclatureLevel.REGION, NomenclatureLevel.FAMILY,
            NomenclatureLevel.SUBFAMILY);

    private final DocumentLineRepository lines;
    private final DocumentImportRepository documentImports;
    private final NomenclatureRepository nomenclatures;
    private final SupplierRepository suppliers;

    public SupplierTurnoverService(DocumentLineRepository lines, DocumentImportRepository documentImports,
            NomenclatureRepository nomenclatures, SupplierRepository suppliers) {
        this.lines = lines;
        this.documentImports = documentImports;
        this.nomenclatures = nomenclatures;
        this.suppliers = suppliers;
    }

    /** Dostawcy z przyjęć PZ sklepu, od ostatniej dostawy. */
    @Transactional(readOnly = true)
    public List<TurnoverSupplier> suppliers(UUID storeId) {
        Map<String, Supplier> known = suppliers.findImported(storeId).stream()
                .collect(Collectors.toMap(Supplier::getCode, Function.identity(), (a, b) -> a));
        return lines.findDeliverySuppliers(storeId).stream()
                .map(row -> {
                    String code = row.getSupplierCode().trim();
                    Supplier supplier = known.get(code);
                    String name = supplier != null ? supplier.getName() : row.getSupplierName();
                    return new TurnoverSupplier(code, clean(name, code), supplier != null ? supplier.getNip() : null,
                            row.getLastDelivery().toLocalDate());
                })
                .sorted(Comparator.comparing(TurnoverSupplier::lastDelivery).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierTurnoverReport report(UUID storeId, String supplierCode, LocalDate from, LocalDate to) {
        if (supplierCode == null || supplierCode.isBlank()) {
            throw ApiException.badRequest("Wybierz dostawcę");
        }
        ReportPeriod.check(from, to);
        String code = supplierCode.trim();

        List<LocalDate> months = new ArrayList<>();
        for (LocalDate month = from.withDayOfMonth(1); !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        Map<LocalDate, Integer> monthIndex = new HashMap<>();
        for (int i = 0; i < months.size(); i++) {
            monthIndex.put(months.get(i), i);
        }

        List<SupplierTurnoverAmount> amounts = lines.sumSupplierTurnover(storeId, code, from, to);
        // Klucz: kod pozycji na danym poziomie (pusty = bez nomenklatury), wartości po miesiącach.
        Map<String, Amount[]> byCode = new HashMap<>();
        Amount[] totals = filled(months.size());
        for (SupplierTurnoverAmount amount : amounts) {
            int month = monthIndex.get(amount.getMonth());
            Amount value = new Amount(amount.getDelivered(), amount.getCorrections(),
                    amount.getDelivered().add(amount.getCorrections()));
            String nomenclature = amount.getNomenclatureCode();
            if (nomenclature.length() < NomenclatureLevel.REGION.codeLength()) {
                add(byCode.computeIfAbsent("", key -> filled(months.size())), month, value);
            } else {
                for (NomenclatureLevel level : LEVELS) {
                    if (nomenclature.length() >= level.codeLength()) {
                        String prefix = nomenclature.substring(0, level.codeLength());
                        add(byCode.computeIfAbsent(prefix, key -> filled(months.size())), month, value);
                    }
                }
            }
            add(totals, month, value);
        }

        Map<String, String> names = byCode.isEmpty() ? Map.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId, byCode.keySet()).stream()
                        .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        // Kody w kolejności drzewa: rejon, pod nim jego rodziny, pod każdą jej podrodziny.
        List<Row> rows = new ArrayList<>();
        new TreeMap<>(byCode).forEach((prefix, values) -> {
            if (prefix.isEmpty()) {
                return;
            }
            NomenclatureLevel level = NomenclatureLevel.ofCode(prefix);
            rows.add(new Row(prefix, level, names.getOrDefault(prefix, "Nomenklatura " + prefix), List.of(values),
                    sum(values)));
        });
        if (byCode.containsKey("")) {
            Amount[] values = byCode.get("");
            rows.add(new Row("", null, "Bez nomenklatury", List.of(values), sum(values)));
        }

        Map<LocalDate, Long> counts = lines.countSupplierDeliveries(storeId, code, from, to).stream()
                .collect(Collectors.toMap(DocumentLineRepository.SupplierDeliveryCount::getMonth,
                        DocumentLineRepository.SupplierDeliveryCount::getDeliveries));
        DocumentLineRepository.UnattachedCorrections unattached = lines.sumUnattachedSupplierCorrections(storeId,
                code, from, to);

        return new SupplierTurnoverReport(code, supplierName(storeId, code), from, to,
                documentImports.findLastPeriodTo(storeId).orElse(null), months,
                months.stream().map(month -> counts.getOrDefault(month, 0L)).toList(), List.of(totals), sum(totals),
                rows, new SupplierTurnoverReport.UnattachedCorrections(unattached.getCount(),
                        unattached.getPurchaseValue()));
    }

    private String supplierName(UUID storeId, String code) {
        return suppliers.findImported(storeId).stream()
                .filter(supplier -> code.equals(supplier.getCode()))
                .map(Supplier::getName)
                .findFirst()
                .or(() -> lines.findDeliverySuppliers(storeId).stream()
                        .filter(row -> code.equals(row.getSupplierCode().trim()))
                        .map(DocumentLineRepository.DeliverySupplier::getSupplierName)
                        .findFirst())
                .map(name -> clean(name, code))
                .orElse(code);
    }

    private static String clean(String name, String code) {
        return name == null || name.isBlank() ? code : name.trim().replaceAll("\\s+", " ");
    }

    private static Amount[] filled(int size) {
        Amount[] values = new Amount[size];
        Collections.nCopies(size, ZERO).toArray(values);
        return values;
    }

    private static void add(Amount[] values, int month, Amount value) {
        values[month] = plus(values[month], value);
    }

    private static Amount plus(Amount a, Amount b) {
        return new Amount(a.delivered().add(b.delivered()), a.corrections().add(b.corrections()),
                a.net().add(b.net()));
    }

    private static Amount sum(Amount[] values) {
        Amount total = ZERO;
        for (Amount value : values) {
            total = plus(total, value);
        }
        return total;
    }
}
