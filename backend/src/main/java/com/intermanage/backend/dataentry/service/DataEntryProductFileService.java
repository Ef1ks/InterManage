package com.intermanage.backend.dataentry.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.XlsWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.ProductFile;
import com.intermanage.backend.dataentry.dto.ProductDraftRequest;
import com.intermanage.backend.dataentry.dto.ProductDraftResult;
import com.intermanage.backend.dataentry.dto.ProductFileRequest;
import com.intermanage.backend.dataentry.entity.ProductFileExport;
import com.intermanage.backend.dataentry.repository.ProductFileExportRepository;
import com.intermanage.backend.product.entity.ProductDraft;
import com.intermanage.backend.product.repository.ProductDraftRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.MercalysSupplierCreation;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.MercalysSupplierCreationRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Plik nowych i zmienionych artykułów (CADBEZP) przy zapisie pliku XML faktury; przeglądarka zapisuje go w folderze
 * z ustawień sklepu. Sam plik niczego nie zapisuje w bazie: dopiero gdy pracownik potwierdzi, że program sklepu go
 * poprawnie wczytał, nowe artykuły z pliku zostają szkicami ({@link ProductDraft}), które rozstrzyga kolejny raport
 * kartoteki ({@code ProductDraftService}).
 */
@Service
public class DataEntryProductFileService {

    /** Nazwa dostawcy w kartotece jest ucięta do 30 znaków (bez końcowych spacji bywa krótsza). */
    private static final int CATALOG_NAME_CUT = 28;

    private final XlsWriter xlsWriter;
    private final SupplierRepository suppliers;
    private final ProductDraftRepository drafts;
    private final StoreRepository stores;
    private final AppUserRepository users;

    private final ProductFileExportRepository exports;
    private final MercalysSupplierCreationRepository creations;

    public DataEntryProductFileService(XlsWriter xlsWriter, SupplierRepository suppliers,
            ProductDraftRepository drafts, StoreRepository stores, AppUserRepository users,
            ProductFileExportRepository exports, MercalysSupplierCreationRepository creations) {
        this.exports = exports;
        this.creations = creations;
        this.xlsWriter = xlsWriter;
        this.suppliers = suppliers;
        this.drafts = drafts;
        this.stores = stores;
        this.users = users;
    }

    @Transactional
    public DataEntryService.ExportFile file(UUID storeId, ProductFileRequest request) {
        byte[] content = xlsWriter.write("Arkusz1", ProductFile.HEADERS, ProductFile.rows(request.rows()));
        String fileName = ProductFile.fileName(supplierName(storeId, request), request.invoiceNumber());
        exports.save(new ProductFileExport(storeId, fileName, sha256(content), supplierCode(storeId, request),
                ProductFile.articles(request.rows())));
        return new DataEntryService.ExportFile(fileName, content);
    }

    /** Wydany plik po treści i nazwie: makro potrzebuje jego dostawcy i stawek VAT, których sam plik nie ma. */
    @Transactional(readOnly = true)
    public Optional<ProductFileExport> exported(UUID storeId, String fileName, byte[] content) {
        return exports.findFirstByStoreIdAndContentSha256AndFileNameOrderByCreatedAtDesc(storeId, sha256(content),
                fileName);
    }

    @Transactional(readOnly = true)
    public List<ProductFileExport> exported(Collection<UUID> ids) {
        return exports.findAllById(ids);
    }

    /**
     * Numer dostawcy w programie sklepu: z listy dostawców, a dostawca założony niedawno w Mercalys makrem (jeszcze nie
     * ma go na wgranej liście) ma numer ze swojej ostatniej rezerwacji. Plik kalkulatora zna tylko nazwę z kartoteki,
     * uciętą do 30 znaków: liczy się tylko jednoznaczny dostawca.
     */
    private String supplierCode(UUID storeId, ProductFileRequest request) {
        Optional<Supplier> supplier = supplier(storeId, request);
        if (supplier.isPresent() && supplier.get().getCode() != null) {
            return supplier.get().getCode();
        }
        String nip = DataEntryLinkService.digits(supplier.map(Supplier::getNip).orElse(request.sellerNip()));
        if (!nip.isEmpty()) {
            return creations.findFirstByStoreIdAndNipAndDeletedAtIsNullOrderByCreatedAtDesc(storeId, nip)
                    .map(MercalysSupplierCreation::getCode).orElse(null);
        }
        if (supplier.isPresent()) {
            return null;
        }
        String name = DataEntryLinkService.code(request.sellerName());
        if (name.isEmpty()) {
            return null;
        }
        List<String> codes = suppliers.findByStore(storeId).stream()
                .filter(found -> found.getCode() != null)
                .filter(found -> {
                    String known = DataEntryLinkService.code(found.getName());
                    return known.equals(name) || (name.length() >= CATALOG_NAME_CUT && known.startsWith(name));
                })
                .map(Supplier::getCode).distinct().toList();
        return codes.size() == 1 ? codes.getFirst() : null;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Program sklepu poprawnie wczytał plik CADBEZP: każdy artykuł z EAN-em, którego nie ma w kartotece, zostaje
     * szkicem z danymi z pliku. Oczekujący szkic tego samego EAN-u zastępuje nowszy (z odnośnikiem, więc „Cofnij”
     * go przywraca); ten sam EAN kilka razy w pliku to jeden szkic (ostatni wiersz).
     */
    @Transactional
    public ProductDraftResult confirm(UUID storeId, ProductDraftRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        ProductFileRequest file = request.file();
        Map<String, ProductFileRequest.Row> byEan = new LinkedHashMap<>();
        int withoutEan = 0;
        for (ProductFileRequest.Row row : file.rows()) {
            String ean = draftEan(row.ean());
            if (ean == null) {
                withoutEan++;
            } else {
                byEan.put(ean, row);
            }
        }
        Set<String> known = byEan.isEmpty() ? Set.of()
                : new HashSet<>(drafts.findKnownEans(storeId, byEan.keySet().toArray(String[]::new)));
        byEan.keySet().removeAll(known);
        if (byEan.isEmpty()) {
            return new ProductDraftResult(List.of(), known.size(), withoutEan);
        }

        AppUser user = users.getReferenceById(userId);
        Map<String, ProductDraft> previous = drafts.findPendingByEans(storeId, byEan.keySet()).stream()
                .collect(Collectors.toMap(ProductDraft::getEan, Function.identity()));
        previous.values().forEach(draft -> draft.removed(ProductDraft.RemovedReason.REPLACED, null, user));
        // Zastąpione przed nowymi: unikalny indeks pozwala na jeden oczekujący szkic EAN-u.
        drafts.flush();

        Optional<Supplier> supplier = supplier(storeId, file);
        String supplierName = supplier.map(Supplier::getName).orElse(file.sellerName());
        List<ProductDraft> created = new ArrayList<>();
        byEan.forEach((ean, row) -> created.add(new ProductDraft(store, ean, text(row.name()), text(row.brand()),
                text(row.unit()), row.netContent(), row.purchasePrice(), row.vatPercent(), row.salePrice(),
                text(row.subfamilyCode()), text(row.cn()), supplier.orElse(null), supplierName,
                text(file.invoiceNumber()), request.fileName().strip(), previous.get(ean), user)));
        drafts.saveAll(created);
        return new ProductDraftResult(created.stream().map(ProductDraft::getId).toList(), known.size(), withoutEan);
    }

    /** „Cofnij” potwierdzenia: szkice dostają znacznik, a szkice, które zastąpiły, wracają do oczekujących. */
    @Transactional
    public void undo(UUID storeId, List<UUID> ids, UUID userId) {
        AppUser user = users.getReferenceById(userId);
        List<ProductDraft> cancelled = drafts.findByIdInAndStoreId(ids, storeId).stream()
                .filter(ProductDraft::isPending)
                .toList();
        cancelled.forEach(draft -> draft.removed(ProductDraft.RemovedReason.CANCELLED, null, user));
        drafts.flush();
        cancelled.stream().map(ProductDraft::getReplaces).filter(draft -> draft != null)
                .forEach(ProductDraft::restoreReplaced);
    }

    /** EAN szkicu: kod z cyfr jak w pliku, bez zer wiodących; kod zastępczy (nazwa, indeks) = brak. */
    private static String draftEan(String ean) {
        if (ean == null || !ean.strip().matches("\\d{1,6}|\\d{8,14}")) {
            return null;
        }
        String stripped = ean.strip().replaceFirst("^0+", "");
        return stripped.isEmpty() ? null : stripped;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Nazwa z listy dostawców sklepu (krótka, jak w programie sklepu), bez niej nazwa sprzedawcy z faktury. */
    private String supplierName(UUID storeId, ProductFileRequest request) {
        return supplier(storeId, request).map(Supplier::getName).orElse(request.sellerName());
    }

    /** Dostawca wybrany w tabeli albo z listy sklepu po NIP-ie sprzedawcy. */
    private Optional<Supplier> supplier(UUID storeId, ProductFileRequest request) {
        if (request.supplierId() != null) {
            return Optional.of(suppliers.findById(request.supplierId())
                    .filter(supplier -> supplier.getStore().getId().equals(storeId))
                    .orElseThrow(() -> ApiException.badRequest("Nie znaleziono dostawcy w tym sklepie")));
        }
        String nip = DataEntryLinkService.digits(request.sellerNip());
        return nip.isEmpty() ? Optional.empty() : suppliers.findByStore(storeId).stream()
                .filter(supplier -> nip.equals(DataEntryLinkService.digits(supplier.getNip())))
                .findFirst();
    }
}
