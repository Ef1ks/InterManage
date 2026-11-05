package com.intermanage.backend.pricecheck.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.pricecheck.dto.ReportSupplier;
import com.intermanage.backend.pricecheck.entity.ReportExcludedSupplier;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.ReportExcludedSupplierRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/** Dostawcy pomijani w raportach weryfikujących zakupy: osobna lista dla każdego raportu, obowiązuje w całym sklepie. */
@Service
public class ReportExcludedSupplierService {

    private static final String LOCK_PREFIX = "report-excluded-suppliers:";

    private final ReportExcludedSupplierRepository excludedSuppliers;
    private final DocumentRepository documents;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceCheckMapper mapper;

    public ReportExcludedSupplierService(ReportExcludedSupplierRepository excludedSuppliers,
            DocumentRepository documents, SupplierRepository suppliers, StoreRepository stores,
            AppUserRepository users, PriceCheckMapper mapper) {
        this.excludedSuppliers = excludedSuppliers;
        this.documents = documents;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    public List<ReportSupplier> active(UUID storeId, ReviewedReport report) {
        return excludedSuppliers.findActive(storeId, report).stream().map(mapper::toResponse).toList();
    }

    /** Dostawcy sklepu do wyboru, alfabetycznie. */
    public List<ReportSupplier> options(UUID storeId) {
        return suppliers.findImported(storeId).stream().map(mapper::toResponse)
                .sorted(Comparator.comparing(ReportSupplier::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Zastępuje listę raportu: nowych dostawców dopisuje, a tych spoza listy oznacza jako usuniętych. */
    @Transactional
    public void replace(UUID storeId, ReviewedReport report, List<String> supplierCodes, UUID userId) {
        documents.lockForImport(LOCK_PREFIX + report + ":" + storeId);
        Set<String> wanted = supplierCodes.stream().map(String::strip)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        Set<String> present = new HashSet<>();
        for (ReportExcludedSupplier item : excludedSuppliers.findActive(storeId, report)) {
            if (wanted.contains(item.getSupplierCode())) {
                present.add(item.getSupplierCode());
            } else {
                item.markRemoved(user);
            }
        }
        List<String> added = wanted.stream().filter(code -> !present.contains(code)).toList();
        if (!added.isEmpty()) {
            Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
            Map<String, String> names = suppliers.findImported(storeId).stream()
                    .collect(Collectors.toMap(Supplier::getCode, Supplier::getName, (a, b) -> a));
            for (String code : added) {
                String name = names.get(code);
                if (name == null) {
                    throw ApiException.badRequest("Nie znaleziono dostawcy o kodzie " + code);
                }
                excludedSuppliers.save(new ReportExcludedSupplier(store, report, code, name.strip(), user));
            }
        }
        excludedSuppliers.flush();
    }
}
