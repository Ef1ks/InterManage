package com.intermanage.backend.dataentry.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.InvoiceXml;
import com.intermanage.backend.dataentry.dto.ExportRequest;
import com.intermanage.backend.dataentry.dto.XmlDocument;
import com.intermanage.backend.dataentry.dto.XmlExportItem;
import com.intermanage.backend.dataentry.entity.DataEntryXmlExport;
import com.intermanage.backend.dataentry.entity.XmlExportKind;
import com.intermanage.backend.dataentry.mapper.DataEntryXmlExportMapper;
import com.intermanage.backend.dataentry.repository.DataEntryXmlExportRepository;
import com.intermanage.backend.ksef.dto.KsefInvoiceSummary;
import com.intermanage.backend.ksef.service.KsefInvoiceService;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Pliki XML faktur w tabeli wprowadzania danych:
 * <ul>
 * <li>"Zapisz XML": plik powstaje jako wpis bez {@code savedAt}, a do historii (100 ostatnich w sklepie) trafia dopiero
 * po potwierdzeniu, że przeglądarka zapisała go na dysku ({@link #confirmSaved}). Nieudany zapis nic nie zmienia.</li>
 * <li>"Zapisz tymczasowo": plik z tabelą z przeglądarki, bez zapisu na dysku, najwyżej 10 w sklepie (starsze dostają
 * znacznik usunięcia).</li>
 * <li>Wczytanie pliku z historii, z zapisów tymczasowych albo z dysku z powrotem do tabeli.</li>
 * </ul>
 */
@Service
public class DataEntryXmlService {

    /** Tyle ostatnich plików pokazuje historia. */
    public static final int HISTORY_SIZE = 100;
    /** Najwięcej zapisów tymczasowych w sklepie. */
    public static final int TEMPORARY_LIMIT = 10;
    /** Plik XML faktury ma kilkadziesiąt kB; większy to pomyłka. */
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private final DataEntryXmlExportRepository exports;
    private final DataEntryXmlExportMapper mapper;
    private final DataEntryService dataEntry;
    private final KsefInvoiceService ksefInvoices;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public DataEntryXmlService(DataEntryXmlExportRepository exports, DataEntryXmlExportMapper mapper,
            DataEntryService dataEntry, KsefInvoiceService ksefInvoices, StoreRepository stores,
            AppUserRepository users) {
        this.exports = exports;
        this.mapper = mapper;
        this.dataEntry = dataEntry;
        this.ksefInvoices = ksefInvoices;
        this.stores = stores;
        this.users = users;
    }

    /** Plik do zapisu na dysku; {@code id} to wpis, który {@link #confirmSaved} doda do historii. */
    public record ExportedFile(UUID id, String fileName, byte[] content) {
    }

    /** Plik XML z tabeli ("Zapisz XML"); do historii trafi dopiero po {@link #confirmSaved}. */
    @Transactional
    public ExportedFile export(UUID storeId, ExportRequest request, UUID userId) {
        DataEntryService.XmlFile file = dataEntry.buildXml(storeId, request);
        DataEntryXmlExport export = exports.save(entity(storeId, XmlExportKind.SAVED, file, null, userId));
        return new ExportedFile(export.getId(), file.fileName(), file.content());
    }

    /**
     * Przeglądarka zapisała plik na dysku: wpis trafia do historii, a wiersze z propozycji AI zapisują wartości
     * z pliku przy propozycji. Ponowne potwierdzenie niczego nie zmienia.
     */
    @Transactional
    public void confirmSaved(UUID storeId, UUID id, ExportRequest request, UUID userId) {
        DataEntryXmlExport export = exports.findByIdAndStoreId(id, storeId)
                .filter(found -> found.getKind() == XmlExportKind.SAVED)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku XML"));
        if (export.getSavedAt() == null) {
            export.markSaved();
            dataEntry.saveExported(storeId, request.rows(), userId);
        }
    }

    /** Zapis tymczasowy tabeli (plik XML i cała tabela z przeglądarki); ponad 10 najstarsze dostają znacznik. */
    @Transactional
    public XmlExportItem saveTemporary(UUID storeId, ExportRequest request, String draft, UUID userId) {
        if (request.rows().isEmpty()) {
            throw ApiException.badRequest("Tabela nie ma pozycji do zapisu");
        }
        DataEntryService.XmlFile file = dataEntry.buildXml(storeId, request);
        DataEntryXmlExport saved = exports.save(entity(storeId, XmlExportKind.TEMPORARY, file, draft, userId));
        exports.flush();
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        List<DataEntryXmlExport> active = exports
                .findByStoreIdAndKindAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId, XmlExportKind.TEMPORARY);
        active.stream().skip(TEMPORARY_LIMIT).forEach(old -> old.delete(user));
        return exports.findTemporary(storeId, PageRequest.of(0, TEMPORARY_LIMIT)).stream()
                .filter(item -> item.getId().equals(saved.getId())).findFirst().map(mapper::toItem)
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<XmlExportItem> history(UUID storeId) {
        return exports.findSaved(storeId, PageRequest.of(0, HISTORY_SIZE)).stream().map(mapper::toItem).toList();
    }

    @Transactional(readOnly = true)
    public List<XmlExportItem> temporary(UUID storeId) {
        return exports.findTemporary(storeId, PageRequest.of(0, TEMPORARY_LIMIT)).stream().map(mapper::toItem)
                .toList();
    }

    /** Usunięcie zapisu tymczasowego to znacznik; zwraca listę po zmianie. */
    @Transactional
    public List<XmlExportItem> deleteTemporary(UUID storeId, UUID id, UUID userId) {
        DataEntryXmlExport export = temporaryEntry(storeId, id);
        if (export.getDeletedAt() == null) {
            export.delete(userId != null ? users.getReferenceById(userId) : null);
        }
        exports.flush();
        return temporary(storeId);
    }

    /** "Przywróć" po usunięciu: wraca na listę (nadmiar ponad 10 znowu dostaje znacznik, najstarszy pierwszy). */
    @Transactional
    public List<XmlExportItem> restoreTemporary(UUID storeId, UUID id, UUID userId) {
        temporaryEntry(storeId, id).restore();
        exports.flush();
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        exports.findByStoreIdAndKindAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId, XmlExportKind.TEMPORARY)
                .stream().skip(TEMPORARY_LIMIT).forEach(old -> old.delete(user));
        exports.flush();
        return temporary(storeId);
    }

    /** Plik z historii albo zapis tymczasowy; wpis niezapisany na dysku nie istnieje. */
    @Transactional(readOnly = true)
    public DataEntryXmlExport file(UUID storeId, UUID id) {
        return exports.findByIdAndStoreId(id, storeId)
                .filter(found -> found.getKind() == XmlExportKind.TEMPORARY || found.getSavedAt() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku XML"));
    }

    /**
     * Plik z historii albo zapis tymczasowy jako dokument do tabeli; faktura z KSeF to ta, która była w tabeli przy
     * zapisie (tylko z dostępem do faktur KSeF i gdy dalej jest widoczna w sklepie). Zapis tymczasowy niesie też tabelę
     * z przeglądarki ({@code draft}).
     */
    @Transactional(readOnly = true)
    public XmlDocument fromHistory(UUID storeId, UUID id, boolean withKsef) {
        DataEntryXmlExport export = file(storeId, id);
        InvoiceXml.Document document = read(export.getContent());
        KsefInvoiceSummary invoice = null;
        if (withKsef && export.getInvoiceId() != null) {
            try {
                invoice = ksefInvoices.summary(storeId, export.getInvoiceId());
            } catch (ApiException e) {
                // Faktura niewidoczna już w sklepie (np. zmiana IDWew): plik wczytuje się bez niej.
            }
        }
        if (invoice == null && withKsef) {
            invoice = matching(storeId, document);
        }
        return result(storeId, export.getFileName(), document, invoice, export.getDraft());
    }

    /** Plik z dysku; faktura z KSeF po NIP-ie dostawcy i numerze z pliku (z dostępem do faktur KSeF). */
    @Transactional(readOnly = true)
    public XmlDocument fromFile(UUID storeId, String fileName, byte[] content, boolean withKsef) {
        if (content.length == 0) {
            throw ApiException.badRequest("Plik jest pusty");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Plik jest za duży (najwyżej 5 MB)");
        }
        InvoiceXml.Document document = read(content);
        return result(storeId, fileName, document, withKsef ? matching(storeId, document) : null, null);
    }

    private DataEntryXmlExport temporaryEntry(UUID storeId, UUID id) {
        return exports.findByIdAndStoreId(id, storeId)
                .filter(found -> found.getKind() == XmlExportKind.TEMPORARY)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zapisu tymczasowego"));
    }

    private DataEntryXmlExport entity(UUID storeId, XmlExportKind kind, DataEntryService.XmlFile file, String draft,
            UUID userId) {
        return new DataEntryXmlExport(storeId, kind, file.invoiceId(), file.fileName(), file.number(), file.date(),
                file.supplierName(), file.supplierNip(), file.lineCount(), file.netValue(), file.content(), draft,
                userId != null ? users.getReferenceById(userId) : null);
    }

    private KsefInvoiceSummary matching(UUID storeId, InvoiceXml.Document document) {
        String nip = DataEntryLinkService.digits(document.header().supplierNip());
        return nip.isEmpty() ? null
                : ksefInvoices.findVisible(storeId, nip, document.header().documentNumber()).orElse(null);
    }

    private XmlDocument result(UUID storeId, String fileName, InvoiceXml.Document document,
            KsefInvoiceSummary invoice, String draft) {
        String storeNumber = stores.findById(storeId).map(store -> store.getNumber()).orElse(null);
        String recipient = document.header().recipientCode();
        boolean otherStore = recipient != null && storeNumber != null && !recipient.equals(storeNumber);
        return new XmlDocument(fileName, recipient, otherStore, dataEntry.fromXml(storeId, document, invoice), draft);
    }

    private static InvoiceXml.Document read(byte[] content) {
        try {
            InvoiceXml.Document document = InvoiceXml.read(content);
            if (document.lines().isEmpty()) {
                throw ApiException.badRequest("Plik XML nie ma pozycji");
            }
            if (document.lines().size() > 2000) {
                throw ApiException.badRequest("Plik XML ma za dużo pozycji (najwyżej 2000)");
            }
            return document;
        } catch (InvoiceXml.InvalidXmlException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
