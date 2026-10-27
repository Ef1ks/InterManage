package com.intermanage.backend.document.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentGratis;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.repository.DocumentGratisRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Rejestracja przeniesienia stanów jako gratisu od dostawcy: wartością gratisu jest sprzedaż brutto dokumentu.
 * Nic nie jest kasowane - cofnięcie to znacznik, zmiana dostawcy cofa poprzedni wpis i dodaje nowy.
 * Każda zmiana zwraca cały dokument.
 */
@Service
public class DocumentGratisService {

    private final DocumentRepository documents;
    private final DocumentGratisRepository gratis;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;
    private final DocumentService documentService;

    public DocumentGratisService(DocumentRepository documents, DocumentGratisRepository gratis,
            SupplierRepository suppliers, AppUserRepository users, DocumentService documentService) {
        this.documents = documents;
        this.gratis = gratis;
        this.suppliers = suppliers;
        this.users = users;
        this.documentService = documentService;
    }

    /** Rejestruje gratis albo zmienia dostawcę już zarejestrowanego. */
    @Transactional
    public DocumentResponse register(UUID storeId, String typeCode, UUID documentId, UUID supplierId, UUID userId) {
        Document document = document(storeId, typeCode, documentId);
        Supplier supplier = suppliers.findInStore(storeId, supplierId)
                .orElseThrow(() -> ApiException.badRequest("Wybierz dostawcę z listy tego sklepu"));
        AppUser user = user(userId);
        DocumentGratis current = gratis.findActive(document.getId()).orElse(null);
        if (current == null || !current.getSupplier().getId().equals(supplier.getId())) {
            if (current != null) {
                current.markDeleted(user);
                gratis.flush();
            }
            gratis.save(new DocumentGratis(document, supplier, user));
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
