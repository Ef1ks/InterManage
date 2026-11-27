package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.SupplierMarginRequest;
import com.intermanage.backend.dataentry.dto.SupplierMarginResponse;
import com.intermanage.backend.dataentry.entity.SupplierMargin;
import com.intermanage.backend.dataentry.mapper.SupplierMarginMapper;
import com.intermanage.backend.dataentry.repository.SupplierMarginRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Stałe marże dostawców: domyślna marża dostawcy i wyjątki na kodach nomenklatury. Wprowadzanie danych bierze je
 * zamiast marży wymaganej na nomenklaturze przy dokumentach tego dostawcy. Zapis zastępuje całe ustawienie
 * dostawcy: zmienione i usunięte wpisy dostają znacznik, niezmienione zostają.
 */
@Service
public class SupplierMarginService {

    private static final Comparator<SupplierMarginResponse> BY_NAME =
            Comparator.comparing(SupplierMarginResponse::supplierName, String.CASE_INSENSITIVE_ORDER);

    private final SupplierMarginRepository margins;
    private final SupplierRepository suppliers;
    private final NomenclatureRepository nomenclatures;
    private final AppUserRepository users;
    private final SupplierMarginMapper mapper;

    public SupplierMarginService(SupplierMarginRepository margins, SupplierRepository suppliers,
            NomenclatureRepository nomenclatures, AppUserRepository users, SupplierMarginMapper mapper) {
        this.margins = margins;
        this.suppliers = suppliers;
        this.nomenclatures = nomenclatures;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SupplierMarginResponse> list(UUID storeId) {
        List<SupplierMargin> active = margins.findByStoreIdAndDeletedAtIsNull(storeId);
        Set<String> codes = active.stream().map(SupplierMargin::getNomenclatureCode).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = codes.isEmpty() ? Map.of()
                : nomenclatures.findByStoreIdAndCodeIn(storeId, codes).stream()
                        .collect(Collectors.toMap(Nomenclature::getCode, Nomenclature::getName, (a, b) -> a));
        Map<UUID, List<SupplierMargin>> bySupplier = active.stream()
                .collect(Collectors.groupingBy(margin -> margin.getSupplier().getId(), LinkedHashMap::new,
                        Collectors.toList()));
        return bySupplier.values().stream().map(group -> response(group, names)).sorted(BY_NAME).toList();
    }

    /**
     * Zastępuje stałą marżę dostawcy. Kod spoza nomenklatury sklepu i ten sam kod dwa razy dają 400; bez domyślnej
     * i bez wyjątków marża dostawcy znika.
     */
    @Transactional
    public List<SupplierMarginResponse> save(UUID storeId, UUID supplierId, SupplierMarginRequest request,
            UUID userId) {
        Supplier supplier = suppliers.findInStore(storeId, supplierId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
        Map<String, BigDecimal> wanted = new HashMap<>();
        if (request.defaultPercent() != null) wanted.put("", request.defaultPercent());
        Set<String> codes = new HashSet<>();
        for (SupplierMarginRequest.Rule rule : request.rules()) {
            if (!codes.add(rule.code())) {
                throw ApiException.badRequest("Nomenklatura " + rule.code() + " jest na liście dwa razy");
            }
            wanted.put(rule.code(), rule.marginPercent());
        }
        if (!codes.isEmpty()) {
            Set<String> known = nomenclatures.findByStoreIdAndCodeIn(storeId, codes).stream()
                    .map(Nomenclature::getCode).collect(Collectors.toSet());
            List<String> unknown = codes.stream().filter(code -> !known.contains(code)).sorted().toList();
            if (!unknown.isEmpty()) {
                throw ApiException.badRequest("Nie ma takiej nomenklatury w sklepie: " + String.join(", ", unknown));
            }
        }

        AppUser by = userId != null ? users.getReferenceById(userId) : null;
        boolean removed = false;
        for (SupplierMargin current : margins.findBySupplierIdAndDeletedAtIsNull(supplierId)) {
            String key = Objects.requireNonNullElse(current.getNomenclatureCode(), "");
            BigDecimal value = wanted.get(key);
            if (value != null && value.compareTo(current.getMarginPercent()) == 0) {
                wanted.remove(key);
            } else {
                current.delete(by);
                removed = true;
            }
        }
        // Znaczniki przed nowymi wpisami: aktywny może być tylko jeden na dostawcę i kod (indeks unikalny).
        if (removed) margins.flush();
        wanted.forEach((code, value) -> margins.save(new SupplierMargin(supplier, code.isEmpty() ? null : code,
                value, by)));
        margins.flush();
        return list(storeId);
    }

    private SupplierMarginResponse response(List<SupplierMargin> group, Map<String, String> names) {
        Supplier supplier = group.getFirst().getSupplier();
        BigDecimal defaultPercent = group.stream().filter(margin -> margin.getNomenclatureCode() == null)
                .map(SupplierMargin::getMarginPercent).findFirst().orElse(null);
        List<SupplierMarginResponse.Rule> rules = group.stream()
                .filter(margin -> margin.getNomenclatureCode() != null)
                .sorted(Comparator.comparing(SupplierMargin::getNomenclatureCode))
                .map(margin -> mapper.toRule(margin, names.get(margin.getNomenclatureCode())))
                .toList();
        SupplierMargin latest = group.stream().max(Comparator.comparing(SupplierMargin::getCreatedAt)).orElseThrow();
        return new SupplierMarginResponse(supplier.getId(), supplier.getName(), supplier.getCode(), defaultPercent,
                rules, latest.getCreatedBy() != null ? latest.getCreatedBy().getDisplayName() : null,
                latest.getCreatedAt());
    }
}
