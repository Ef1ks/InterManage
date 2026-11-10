package com.intermanage.backend.document.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.entity.DocumentVerificationChange;
import com.intermanage.backend.document.entity.DocumentVerificationStatus;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentVerificationChangeRepository;

/**
 * Weryfikacja przyjęć PZ: „w trakcie weryfikacji” (czeka na kierownika) albo „zweryfikowane”. Status jest przy
 * dokumencie, a każda zmiana, także zdjęcie statusu, trafia do historii ({@code document_verification_changes}).
 */
@Service
public class DocumentVerificationService {

    private final DocumentRepository documents;
    private final DocumentVerificationChangeRepository changes;
    private final AppUserRepository users;
    private final DocumentService documentService;

    public DocumentVerificationService(DocumentRepository documents, DocumentVerificationChangeRepository changes,
            AppUserRepository users, DocumentService documentService) {
        this.documents = documents;
        this.changes = changes;
        this.users = users;
        this.documentService = documentService;
    }

    /** Ustawia status weryfikacji ({@code null} zdejmuje); zwraca dokument po zmianie. */
    @Transactional
    public DocumentResponse set(UUID storeId, String typeCode, UUID id, DocumentVerificationStatus status,
            UUID userId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        if (type != DocumentType.PZ) {
            throw ApiException.badRequest("Weryfikację można oznaczyć tylko w przyjęciu towaru");
        }
        Document document = documents.findHeader(storeId, type, id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        var user = userId != null ? users.getReferenceById(userId) : null;
        if (document.setVerification(status, user)) {
            changes.save(new DocumentVerificationChange(document, status, user));
            documents.flush();
        }
        return documentService.document(storeId, typeCode, id);
    }
}
