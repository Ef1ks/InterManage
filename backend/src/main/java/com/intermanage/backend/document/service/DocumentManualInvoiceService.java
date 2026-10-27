package com.intermanage.backend.document.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.dto.ManualInvoice;
import com.intermanage.backend.document.dto.ManualInvoiceRequest;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentManualInvoice;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentManualInvoiceRepository;
import com.intermanage.backend.document.repository.DocumentRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Faktury spoza KSeF wpisywane ręcznie przy przyjęciach PZ i ich korektach: dostawca z listy dostawców sklepu
 * i numer faktury. Przyjęcie ma najwyżej jedną fakturę - z KSeF (przypiętą albo dopasowaną po numerze) albo taką;
 * korekta może mieć kilka. Usunięcie to znacznik, przywrócenie zdejmuje go.
 */
@Service
public class DocumentManualInvoiceService {

    private final DocumentManualInvoiceRepository invoices;
    private final DocumentRepository documents;
    private final SupplierRepository suppliers;
    private final DocumentKsefLinkService ksefLinks;
    private final AppUserRepository users;
    private final DocumentMapper mapper;

    public DocumentManualInvoiceService(DocumentManualInvoiceRepository invoices, DocumentRepository documents,
            SupplierRepository suppliers, DocumentKsefLinkService ksefLinks, AppUserRepository users,
            DocumentMapper mapper) {
        this.invoices = invoices;
        this.documents = documents;
        this.suppliers = suppliers;
        this.ksefLinks = ksefLinks;
        this.users = users;
        this.mapper = mapper;
    }

    /** Faktury spoza KSeF dokumentów, od najstarszej. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ManualInvoice>> invoicesOf(Collection<Document> page) {
        List<UUID> ids = page.stream().filter(document -> document.getType().canHaveManualInvoice())
                .map(Document::getId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return invoices.findActiveByDocumentIds(ids).stream().collect(Collectors.groupingBy(
                invoice -> invoice.getDocument().getId(), LinkedHashMap::new,
                Collectors.mapping(mapper::toManualInvoice, Collectors.toList())));
    }

    /** Dopisuje fakturę; ta sama (dostawca i numer) już wpisana zostaje bez zmian. */
    @Transactional
    public void add(UUID storeId, String typeCode, UUID documentId, ManualInvoiceRequest request, UUID userId) {
        Document document = document(storeId, typeCode, documentId);
        Supplier supplier = suppliers.findInStore(storeId, request.supplierId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dostawcy"));
        String number = request.invoiceNumber().strip();
        List<DocumentManualInvoice> current = invoices.findActiveByDocumentIds(List.of(document.getId()));
        if (current.stream().anyMatch(invoice -> invoice.getSupplier().getId().equals(supplier.getId())
                && invoice.getInvoiceNumber().equalsIgnoreCase(number))) {
            return;
        }
        requireNoInvoice(storeId, document, current);
        invoices.save(new DocumentManualInvoice(document, supplier, number, request.issueDate(), user(userId)));
    }

    @Transactional
    public void remove(UUID storeId, String typeCode, UUID documentId, UUID invoiceId, UUID userId) {
        Document document = document(storeId, typeCode, documentId);
        invoices.findInDocument(document.getId(), invoiceId).filter(invoice -> invoice.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono faktury"))
                .markRemoved(user(userId));
    }

    @Transactional
    public void restore(UUID storeId, String typeCode, UUID documentId, UUID invoiceId) {
        Document document = document(storeId, typeCode, documentId);
        DocumentManualInvoice invoice = invoices.findInDocument(document.getId(), invoiceId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono faktury"));
        if (invoice.getRemovedAt() == null) {
            return;
        }
        requireNoInvoice(storeId, document, invoices.findActiveByDocumentIds(List.of(document.getId())));
        invoice.restore();
    }

    /** Przyjęcie PZ z fakturą (z KSeF albo spoza) nie dostaje drugiej. */
    private void requireNoInvoice(UUID storeId, Document document, List<DocumentManualInvoice> current) {
        if (document.getType() != DocumentType.PZ) {
            return;
        }
        String existing = !current.isEmpty() ? current.getFirst().getInvoiceNumber()
                : ksefLinks.invoicesOf(storeId, List.of(document)).getOrDefault(document.getId(), List.of())
                        .stream().map(LinkedKsefInvoice::invoiceNumber).findFirst().orElse(null);
        if (existing != null) {
            throw ApiException.conflict("Przyjęcie " + document.getNumber() + " ma już fakturę " + existing
                    + ". Do przyjęcia przypina się jedną fakturę.");
        }
    }

    private Document document(UUID storeId, String typeCode, UUID documentId) {
        DocumentType type = DocumentType.fromCode(typeCode)
                .orElseThrow(() -> ApiException.notFound("Nieznany typ dokumentu"));
        if (!type.canHaveManualInvoice()) {
            throw ApiException.badRequest("Fakturę spoza KSeF wpisuje się do przyjęć i ich korekt");
        }
        return documents.findHeader(storeId, type, documentId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu"));
    }

    private AppUser user(UUID userId) {
        return userId != null ? users.getReferenceById(userId) : null;
    }
}
