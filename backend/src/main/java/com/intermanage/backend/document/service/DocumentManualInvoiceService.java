package com.intermanage.backend.document.service;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.dto.LinkedKsefInvoice;
import com.intermanage.backend.document.dto.ManualInvoice;
import com.intermanage.backend.document.dto.ManualInvoiceFile;
import com.intermanage.backend.document.dto.ManualInvoiceRequest;
import com.intermanage.backend.document.entity.Document;
import com.intermanage.backend.document.entity.DocumentManualInvoice;
import com.intermanage.backend.document.entity.DocumentManualInvoiceFile;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.document.mapper.DocumentMapper;
import com.intermanage.backend.document.repository.DocumentManualInvoiceFileRepository;
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

    /** Największy plik faktury: skan kilku stron w PDF zwykle mieści się w 2-3 MB. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final DocumentManualInvoiceRepository invoices;
    private final DocumentManualInvoiceFileRepository files;
    private final DocumentRepository documents;
    private final SupplierRepository suppliers;
    private final DocumentKsefLinkService ksefLinks;
    private final AppUserRepository users;
    private final DocumentMapper mapper;

    public DocumentManualInvoiceService(DocumentManualInvoiceRepository invoices, DocumentRepository documents,
            SupplierRepository suppliers, DocumentKsefLinkService ksefLinks, AppUserRepository users,
            DocumentMapper mapper, DocumentManualInvoiceFileRepository files) {
        this.invoices = invoices;
        this.files = files;
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
        List<DocumentManualInvoice> found = invoices.findActiveByDocumentIds(ids);
        Map<UUID, ManualInvoiceFile> filesByInvoice = found.isEmpty() ? Map.of()
                : files.findActiveSummaries(found.stream().map(DocumentManualInvoice::getId).toList()).stream()
                        .collect(Collectors.toMap(DocumentManualInvoiceFileRepository.Summary::getInvoiceId,
                                mapper::toManualInvoiceFile));
        return found.stream().collect(Collectors.groupingBy(
                invoice -> invoice.getDocument().getId(), LinkedHashMap::new,
                Collectors.mapping(invoice -> mapper.toManualInvoice(invoice, filesByInvoice.get(invoice.getId())),
                        Collectors.toList())));
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

    /**
     * Dołącza plik do faktury (PDF albo zdjęcie JPG, PNG, rozpoznane po treści, do {@link #MAX_FILE_BYTES});
     * poprzedni plik dostaje znacznik i zostaje jako historia.
     */
    @Transactional
    public void attachFile(UUID storeId, String typeCode, UUID documentId, UUID invoiceId, MultipartFile upload,
            UUID userId) {
        DocumentManualInvoice invoice = activeInvoice(storeId, typeCode, documentId, invoiceId);
        if (upload == null || upload.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik faktury");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej " + MAX_FILE_BYTES / 1024 / 1024 + " MB)");
        }
        byte[] content;
        try {
            content = upload.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("Nie udało się odczytać pliku");
        }
        String contentType = contentType(content);
        if (contentType == null) {
            throw ApiException.badRequest("Dołącz plik PDF albo zdjęcie (JPG, PNG)");
        }
        AppUser user = user(userId);
        files.findActive(invoice.getId()).ifPresent(previous -> {
            previous.markRemoved(user);
            // Najpierw znacznik, potem nowy plik: w bazie faktura ma najwyżej jeden aktywny.
            files.flush();
        });
        files.save(new DocumentManualInvoiceFile(invoice, fileName(upload.getOriginalFilename(), contentType),
                contentType, content, user));
    }

    /** Usuwa plik faktury (znacznik); „Przywróć” to {@link #restoreFile}. */
    @Transactional
    public void removeFile(UUID storeId, String typeCode, UUID documentId, UUID invoiceId, UUID userId) {
        DocumentManualInvoice invoice = activeInvoice(storeId, typeCode, documentId, invoiceId);
        files.findActive(invoice.getId())
                .orElseThrow(() -> ApiException.notFound("Faktura nie ma pliku"))
                .markRemoved(user(userId));
    }

    /** Przywraca ostatnio usunięty plik, gdy faktura nie ma innego. */
    @Transactional
    public void restoreFile(UUID storeId, String typeCode, UUID documentId, UUID invoiceId) {
        DocumentManualInvoice invoice = activeInvoice(storeId, typeCode, documentId, invoiceId);
        if (files.findActive(invoice.getId()).isPresent()) {
            throw ApiException.conflict("Faktura ma już inny plik");
        }
        files.findFirstByInvoiceIdAndRemovedAtIsNotNullOrderByRemovedAtDesc(invoice.getId())
                .orElseThrow(() -> ApiException.notFound("Nie ma pliku do przywrócenia"))
                .restore();
    }

    /** Dołączony plik do pobrania albo wydruku. */
    @Transactional(readOnly = true)
    public DocumentService.ExportFile file(UUID storeId, String typeCode, UUID documentId, UUID invoiceId) {
        DocumentManualInvoice invoice = activeInvoice(storeId, typeCode, documentId, invoiceId);
        DocumentManualInvoiceFile file = files.findActive(invoice.getId())
                .orElseThrow(() -> ApiException.notFound("Faktura nie ma pliku"));
        return new DocumentService.ExportFile(file.getFileName(), file.getContentType(), file.getContent());
    }

    private DocumentManualInvoice activeInvoice(UUID storeId, String typeCode, UUID documentId, UUID invoiceId) {
        Document document = document(storeId, typeCode, documentId);
        return invoices.findInDocument(document.getId(), invoiceId).filter(invoice -> invoice.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono faktury"));
    }

    /** Typ pliku z pierwszych bajtów, nie z nazwy ani nagłówka przeglądarki; {@code null} = nieobsługiwany. */
    static String contentType(byte[] content) {
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46)) {
            return "application/pdf";
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Nazwa pliku bez ścieżki (niektóre przeglądarki ją dodają), z rozszerzeniem zgodnym z treścią. */
    private static String fileName(String original, String contentType) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        String extension = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            default -> ".png";
        };
        if (name.isEmpty()) {
            name = "faktura" + extension;
        }
        return name.length() > 255 ? name.substring(0, 255 - extension.length()) + extension : name;
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
