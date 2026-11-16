package com.intermanage.backend.pricecheck.service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.pricecheck.dto.ReportExclusions;
import com.intermanage.backend.pricecheck.entity.ReportExcludedNomenclature;
import com.intermanage.backend.pricecheck.entity.ReviewedReport;
import com.intermanage.backend.pricecheck.repository.ReportExcludedNomenclatureRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Nomenklatury i dostawcy pomijani w raportach weryfikujących zakupy: osobne listy dla każdego raportu, wspólne dla
 * sklepu. Artykuł z nomenklaturą zaczynającą się od pomijanego kodu (rejon pomija swoje rodziny i podrodziny) albo od
 * pomijanego dostawcy nie trafia do raportu. Usunięcie z listy to znacznik.
 */
@Service
public class ReportExclusionService {

    private static final String LOCK_PREFIX = "report-excluded-nomenclatures:";

    private final ReportExcludedNomenclatureRepository nomenclatureExclusions;
    private final ReportExcludedSupplierService suppliers;
    private final NomenclatureRepository nomenclatures;
    private final DocumentRepository documents;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public ReportExclusionService(ReportExcludedNomenclatureRepository nomenclatureExclusions,
            ReportExcludedSupplierService suppliers, NomenclatureRepository nomenclatures,
            DocumentRepository documents, StoreRepository stores, AppUserRepository users) {
        this.nomenclatureExclusions = nomenclatureExclusions;
        this.suppliers = suppliers;
        this.nomenclatures = nomenclatures;
        this.documents = documents;
        this.stores = stores;
        this.users = users;
    }

    /** Obie listy raportu z dostawcami do wyboru. */
    @Transactional(readOnly = true)
    public ReportExclusions get(UUID storeId, ReviewedReport report) {
        return new ReportExclusions(codes(storeId, report), suppliers.active(storeId, report),
                suppliers.options(storeId));
    }

    /** Kody pomijanych nomenklatur raportu (2, 4 albo 6 cyfr), rosnąco. */
    public List<String> codes(UUID storeId, ReviewedReport report) {
        return nomenclatureExclusions.findActive(storeId, report).stream().map(ReportExcludedNomenclature::getCode)
                .toList();
    }

    /** Kody pomijanych dostawców raportu. */
    public Set<String> supplierCodes(UUID storeId, ReviewedReport report) {
        Set<String> codes = new HashSet<>();
        suppliers.active(storeId, report).forEach(supplier -> codes.add(supplier.code()));
        return codes;
    }

    /** Czy kod nomenklatury artykułu (z odstępami albo bez) należy do którejś z pomijanych. */
    public static boolean matches(String nomenclatureCode, List<String> codes) {
        if (nomenclatureCode == null || codes.isEmpty()) {
            return false;
        }
        String digits = nomenclatureCode.replaceAll("\\D", "");
        return codes.stream().anyMatch(digits::startsWith);
    }

    /** Zastępuje listę nomenklatur raportu: nowe kody dopisuje, a te spoza listy oznacza jako usunięte. */
    @Transactional
    public ReportExclusions replaceNomenclatures(UUID storeId, ReviewedReport report, List<String> codes,
            UUID userId) {
        documents.lockForImport(LOCK_PREFIX + report + ":" + storeId);
        Set<String> wanted = codes.stream().map(code -> code.replaceAll("\\s", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> known = nomenclatures.findAllByStore(storeId).stream().map(Nomenclature::getCode)
                .collect(Collectors.toSet());
        List<String> unknown = wanted.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Nie ma takiej nomenklatury w sklepie: " + String.join(", ", unknown));
        }
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        Set<String> present = new HashSet<>();
        for (ReportExcludedNomenclature item : nomenclatureExclusions.findActive(storeId, report)) {
            if (wanted.contains(item.getCode())) {
                present.add(item.getCode());
            } else {
                item.markRemoved(user);
            }
        }
        nomenclatureExclusions.flush();
        for (String code : wanted) {
            if (!present.contains(code)) {
                nomenclatureExclusions.save(new ReportExcludedNomenclature(stores.getReferenceById(storeId), report,
                        code, user));
            }
        }
        nomenclatureExclusions.flush();
        return get(storeId, report);
    }

    /** Zastępuje listę dostawców raportu. */
    @Transactional
    public ReportExclusions replaceSuppliers(UUID storeId, ReviewedReport report, List<String> supplierCodes,
            UUID userId) {
        suppliers.replace(storeId, report, supplierCodes, userId);
        return get(storeId, report);
    }
}
