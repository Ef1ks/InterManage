package com.intermanage.backend.document.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.IncomingTransfer;
import com.intermanage.backend.document.dto.IncomingTransfers;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentTransferTarget;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.document.repository.DocumentTransferTargetRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StorePartnerRepository;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Ruch międzymagazynowy (MM) wystawiony dla sklepu zaprzyjaźnionego. Sklep wysyłający wskazuje sklep docelowy
 * (tylko zaakceptowany sklep zaprzyjaźniony), a sklep docelowy widzi dokument z liniami do wprowadzenia u siebie
 * i oznacza go jako wprowadzony. Nic nie jest kasowane: cofnięcie to znacznik, zmiana sklepu cofa poprzedni wpis.
 */
@Service
public class DocumentTransferService {

    /** Ile ostatnio wprowadzonych dokumentów pokazuje sklep docelowy pod czekającymi. */
    static final int RECENTLY_ENTERED = 30;

    private final DocumentRepository documents;
    private final DocumentTransferTargetRepository targets;
    private final StoreRepository stores;
    private final StorePartnerRepository partners;
    private final AppUserRepository users;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;

    public DocumentTransferService(DocumentRepository documents, DocumentTransferTargetRepository targets,
            StoreRepository stores, StorePartnerRepository partners, AppUserRepository users,
            DocumentService documentService, DocumentMapper documentMapper) {
        this.documents = documents;
        this.targets = targets;
        this.stores = stores;
        this.partners = partners;
        this.users = users;
        this.documentService = documentService;
        this.documentMapper = documentMapper;
    }

    /** Wskazuje sklep docelowy albo go zmienia; zmiana zeruje "wprowadzony", bo to inny sklep. */
    @Transactional
    public DocumentResponse setTarget(UUID storeId, String typeCode, UUID documentId, UUID targetStoreId,
            UUID userId) {
        Document document = transfer(storeId, typeCode, documentId);
        Store target = stores.findById(targetStoreId).filter(Store::isActive)
                .orElseThrow(() -> ApiException.badRequest("Wybrany sklep nie istnieje albo jest wyłączony"));
        if (!partners.arePartners(storeId, targetStoreId)) {
            throw ApiException.badRequest(
                    "Dokument można wystawić tylko dla sklepu zaprzyjaźnionego (Ustawienia sklepu)");
        }
        AppUser user = user(userId);
        DocumentTransferTarget current = targets.findActive(document.getId()).orElse(null);
        if (current == null || !current.getTargetStore().getId().equals(targetStoreId)) {
            if (current != null) {
                current.markDeleted(user);
                targets.flush();
            }
            targets.save(new DocumentTransferTarget(document, target, user));
        }
        return documentService.document(storeId, typeCode, documentId);
    }

    @Transactional
    public DocumentResponse cancel(UUID storeId, String typeCode, UUID documentId, UUID userId) {
        Document document = transfer(storeId, typeCode, documentId);
        targets.findActive(document.getId())
                .orElseThrow(() -> ApiException.notFound("Dokument nie jest wystawiony dla innego sklepu"))
                .markDeleted(user(userId));
        return documentService.document(storeId, typeCode, documentId);
    }

    /** Przywraca ostatnio cofnięty sklep docelowy ("Przywróć" w powiadomieniu), razem z "wprowadzony". */
    @Transactional
    public DocumentResponse restore(UUID storeId, String typeCode, UUID documentId) {
        Document document = transfer(storeId, typeCode, documentId);
        if (targets.findActive(document.getId()).isPresent()) {
            throw ApiException.conflict("Dokument jest już wystawiony dla sklepu");
        }
        targets.findFirstByDocumentIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(document.getId())
                .orElseThrow(() -> ApiException.notFound("Nie ma czego przywrócić"))
                .restore();
        targets.flush();
        return documentService.document(storeId, typeCode, documentId);
    }

    /** Dokumenty innych sklepów wystawione dla sklepu: czekające (wszystkie) i ostatnio wprowadzone. */
    @Transactional(readOnly = true)
    public IncomingTransfers incoming(UUID storeId) {
        return new IncomingTransfers(
                targets.findPending(storeId).stream().map(target -> documentMapper.toIncoming(target, List.of()))
                        .toList(),
                targets.findEntered(storeId, Limit.of(RECENTLY_ENTERED)).stream()
                        .map(target -> documentMapper.toIncoming(target, List.of())).toList());
    }

    /** Ile dokumentów czeka na wprowadzenie (plakietka w menu). */
    @Transactional(readOnly = true)
    public long pendingCount(UUID storeId) {
        return targets.countByTargetStoreIdAndDeletedAtIsNullAndEnteredAtIsNull(storeId);
    }

    /** Dokument innego sklepu z liniami; sklep docelowy nie ma dostępu do sklepu wysyłającego, tylko do tego wpisu. */
    @Transactional(readOnly = true)
    public IncomingTransfer incomingTransfer(UUID storeId, UUID targetId) {
        DocumentTransferTarget target = incoming(storeId, targetId);
        Document document = documents.findWithLines(target.getDocument().getStore().getId(), DocumentType.MM,
                        target.getDocument().getId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        return documentMapper.toIncoming(target, document.getLines());
    }

    @Transactional
    public IncomingTransfer markEntered(UUID storeId, UUID targetId, boolean entered, UUID userId) {
        incoming(storeId, targetId).markEntered(user(userId), entered);
        targets.flush();
        return incomingTransfer(storeId, targetId);
    }

    private DocumentTransferTarget incoming(UUID storeId, UUID targetId) {
        return targets.findIncoming(storeId, targetId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private Document transfer(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        Document document = documents.findHeader(storeId, type, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
        if (type != DocumentType.MM) {
            throw ApiException.badRequest("Dla innego sklepu wystawia się ruch międzymagazynowy");
        }
        return document;
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
