package com.intermanage.backend.document.service;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.GratisSupplierSuggestion;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratis;
import com.intermanage.backend.document.entity.DocumentLine;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.entity.GratisKind;
import com.intermanage.backend.document.repository.DocumentGratisRepository;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Rejestracja przeniesienia stanów jako gratisu od dostawcy: wartością gratisu jest sprzedaż brutto dokumentu.
 * Nic nie jest kasowane - cofnięcie to znacznik, zmiana dostawcy, rodzaju albo miesiąca cofa poprzedni wpis i dodaje
 * nowy. Każda zmiana zwraca cały dokument.
 */
@Service
public class DocumentGratisService {

    /** Ilu ostatnich dostawców artykułów z dokumentu podpowiadamy. */
    static final int SUGGESTED_SUPPLIERS = 5;
    /** Baza: przywozi artykuły wielu producentów, więc podpowiedź rozbija ją na marki. */
    static final List<String> BRAND_SUPPLIER_CODES = List.of("538", "88110");

    private final DocumentRepository documents;
    private final DocumentLineRepository documentLines;
    private final DocumentGratisRepository gratis;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;
    private final DocumentService documentService;

    public DocumentGratisService(DocumentRepository documents, DocumentLineRepository documentLines,
            DocumentGratisRepository gratis, SupplierRepository suppliers, AppUserRepository users,
            DocumentService documentService) {
        this.documents = documents;
        this.documentLines = documentLines;
        this.gratis = gratis;
        this.suppliers = suppliers;
        this.users = users;
        this.documentService = documentService;
    }

    /**
     * Rejestruje gratis albo zmienia dostawcę, rodzaj lub miesiąc już zarejestrowanego. Miesiąc równy miesiącowi
     * daty dokumentu zapisujemy jako pusty (bez ręcznego wyboru).
     */
    @Transactional
    public DocumentResponse register(UUID storeId, String typeCode, UUID documentId, UUID supplierId,
            GratisKind kind, YearMonth reportMonth, UUID userId) {
        Document document = document(storeId, typeCode, documentId);
        Supplier supplier = suppliers.findInStore(storeId, supplierId)
                .orElseThrow(() -> ApiException.badRequest("Wybierz dostawcę z listy tego sklepu"));
        YearMonth month = YearMonth.from(document.getDocumentDate()).equals(reportMonth) ? null : reportMonth;
        AppUser user = user(userId);
        DocumentGratis current = gratis.findActive(document.getId()).orElse(null);
        if (current == null || !current.getSupplier().getId().equals(supplier.getId()) || current.getKind() != kind
                || !Objects.equals(current.getReportMonth(), month)) {
            if (current != null) {
                current.markDeleted(user);
                gratis.flush();
            }
            gratis.save(new DocumentGratis(document, supplier, kind, month, user));
        }
        return documentService.document(storeId, typeCode, documentId);
    }

    @Transactional
    public DocumentResponse cancel(UUID storeId, String typeCode, UUID documentId, UUID userId) {
        Document document = document(storeId, typeCode, documentId);
        gratis.findActive(document.getId())
                .orElseThrow(() -> ApiException.notFound("Ten dokument nie jest zarejestrowany jako gratis"))
                .markDeleted(user(userId));
        return documentService.document(storeId, typeCode, documentId);
    }

    /** Przywraca ostatnio cofnięty gratis ("Przywróć" w powiadomieniu). */
    @Transactional
    public DocumentResponse restore(UUID storeId, String typeCode, UUID documentId) {
        Document document = document(storeId, typeCode, documentId);
        if (gratis.findActive(document.getId()).isPresent()) {
            throw ApiException.conflict("Dokument jest już zarejestrowany jako gratis");
        }
        gratis.findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(document.getId())
                .orElseThrow(() -> ApiException.notFound("Nie ma gratisu do przywrócenia"))
                .restore();
        return documentService.document(storeId, typeCode, documentId);
    }

    /**
     * Ostatni dostawcy artykułów z dokumentu (bez pozycji GRATIS) wg przyjęć PZ, od najświeższej dostawy; baza
     * rozbita na marki. Dostawca z listy sklepu dostaje {@code supplierId}, żeby dało się go od razu wybrać.
     */
    @Transactional(readOnly = true)
    public List<GratisSupplierSuggestion> supplierSuggestions(UUID storeId, String typeCode, UUID documentId) {
        Document document = document(storeId, typeCode, documentId);
        List<String> itm8s = document.getLines().stream()
                .filter(line -> !line.isGratisItem())
                .map(DocumentLine::getItm8)
                .distinct()
                .toList();
        if (itm8s.isEmpty()) {
            return List.of();
        }
        Map<String, Supplier> byCode = suppliers.findImported(storeId).stream()
                .collect(Collectors.toMap(Supplier::getCode, Function.identity(), (a, b) -> a));
        return documentLines.findRecentSuppliers(storeId, itm8s, BRAND_SUPPLIER_CODES, SUGGESTED_SUPPLIERS).stream()
                .map(row -> {
                    Supplier supplier = byCode.get(row.getSupplierCode());
                    return new GratisSupplierSuggestion(supplier != null ? supplier.getId() : null,
                            row.getSupplierCode(), supplier != null ? supplier.getName() : row.getSupplierName(),
                            row.getBrand(), row.getLastDeliveryDate(), row.getProductCount());
                })
                .toList();
    }

    private Document document(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        Document document = documents.findById(documentId)
                .filter(found -> found.getStore().getId().equals(storeId) && found.getType() == type)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (!type.canBeGratis()) {
            throw ApiException.badRequest("Jako gratis można zarejestrować tylko przeniesienie stanów");
        }
        return document;
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
