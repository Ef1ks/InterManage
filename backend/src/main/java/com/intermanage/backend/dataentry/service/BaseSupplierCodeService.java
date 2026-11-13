package com.intermanage.backend.dataentry.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.dto.BaseSupplierCodeItem;
import com.intermanage.backend.dataentry.dto.BaseSupplierCodeRequest;
import com.intermanage.backend.dataentry.mapper.DataEntryInvoiceMapper;
import com.intermanage.backend.ksef.entity.KsefBaseSupplierCode;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.repository.KsefBaseSupplierCodeRepository;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Kody dostawców z refaktur bazy SCA PR ("DDDKodDostawcy" w XML) przypisane do dostawców, wspólnie dla firmy (NIP
 * połączenia KSeF sklepu). Po przypisaniu wyszukiwarka faktur znajduje refaktury po nazwie dostawcy (np. "artek").
 * Zmiana przypisania i usunięcie to znacznik na poprzednim.
 */
@Service
public class BaseSupplierCodeService {

    private final KsefBaseSupplierCodeRepository codes;
    private final KsefConnectionRepository connections;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;
    private final DataEntryInvoiceMapper mapper;

    public BaseSupplierCodeService(KsefBaseSupplierCodeRepository codes, KsefConnectionRepository connections,
            SupplierRepository suppliers, AppUserRepository users, DataEntryInvoiceMapper mapper) {
        this.codes = codes;
        this.connections = connections;
        this.suppliers = suppliers;
        this.users = users;
        this.mapper = mapper;
    }

    /** Kody z refaktur firmy (najpierw nieprzypisane, potem od największej liczby faktur) i przypisania bez faktur. */
    @Transactional(readOnly = true)
    public List<BaseSupplierCodeItem> list(UUID storeId) {
        String nip = nip(storeId);
        Map<String, KsefBaseSupplierCode> assigned = new LinkedHashMap<>();
        codes.findByNipAndDeletedAtIsNull(nip).forEach(code -> assigned.put(code.getCode(), code));
        List<BaseSupplierCodeItem> result = new ArrayList<>();
        for (KsefBaseSupplierCodeRepository.InvoiceCode found : codes.findInvoiceCodes(nip)) {
            KsefBaseSupplierCode code = assigned.remove(KsefBaseSupplierCode.normalize(found.getCode()));
            result.add(new BaseSupplierCodeItem(found.getCode(), found.getInvoices(), found.getLastIssueDate(),
                    code != null ? mapper.toAssignment(code) : null));
        }
        assigned.values().forEach(code -> result.add(
                new BaseSupplierCodeItem(code.getCode(), 0, null, mapper.toAssignment(code))));
        result.sort(Comparator.comparing((BaseSupplierCodeItem item) -> item.assignment() != null)
                .thenComparing(BaseSupplierCodeItem::invoices, Comparator.reverseOrder())
                .thenComparing(BaseSupplierCodeItem::code));
        return result;
    }

    /** Przypisanie kodu; poprzednie przypisanie tego kodu dostaje znacznik. */
    @Transactional
    public BaseSupplierCodeItem.Assignment assign(UUID storeId, String code, BaseSupplierCodeRequest request,
            UUID userId) {
        String nip = nip(storeId);
        String normalized = KsefBaseSupplierCode.normalize(code);
        if (normalized == null || normalized.isEmpty() || normalized.length() > 20) {
            throw ApiException.badRequest("Podaj kod dostawcy z bazy");
        }
        String name;
        String supplierNip = null;
        UUID supplierId = null;
        if (request.supplierId() != null) {
            Supplier supplier = suppliers.findInStore(storeId, request.supplierId())
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
            name = supplier.getName();
            supplierNip = supplier.getNip();
            supplierId = supplier.getId();
        } else if (request.name() != null && !request.name().isBlank()) {
            name = request.name().strip();
        } else {
            throw ApiException.badRequest("Wybierz dostawcę albo wpisz nazwę");
        }
        AppUser user = user(userId);
        codes.findByNipAndCodeAndDeletedAtIsNull(nip, normalized).ifPresent(previous -> {
            previous.delete(user);
            codes.flush();
        });
        return mapper.toAssignment(
                codes.saveAndFlush(new KsefBaseSupplierCode(nip, normalized, name, supplierNip, supplierId, user)));
    }

    @Transactional
    public void remove(UUID storeId, UUID id, UUID userId) {
        KsefBaseSupplierCode code = codes.findByIdAndNip(id, nip(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypisania"));
        if (!code.isDeleted()) {
            code.delete(user(userId));
        }
    }

    /**
     * Przywrócenie przypisania („Przywróć” po usunięciu albo zmianie); bieżące przypisanie tego kodu dostaje
     * znacznik.
     */
    @Transactional
    public BaseSupplierCodeItem.Assignment restore(UUID storeId, UUID id, UUID userId) {
        String nip = nip(storeId);
        KsefBaseSupplierCode code = codes.findByIdAndNip(id, nip)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono przypisania"));
        if (code.isDeleted()) {
            codes.findByNipAndCodeAndDeletedAtIsNull(nip, code.getCode()).ifPresent(active -> {
                active.delete(user(userId));
                codes.flush();
            });
            code.restore();
            codes.flush();
        }
        return mapper.toAssignment(code);
    }

    private String nip(UUID storeId) {
        return connections.findById(storeId).map(KsefConnection::getNip)
                .orElseThrow(() -> ApiException.conflict("Sklep nie ma połączenia z KSeF"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
