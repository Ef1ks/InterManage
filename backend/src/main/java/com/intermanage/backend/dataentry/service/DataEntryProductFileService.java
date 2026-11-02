package com.intermanage.backend.dataentry.service;

import java.util.ArrayList;
import java.util.HashSet;
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
import com.intermanage.backend.product.entity.ProductDraft;
import com.intermanage.backend.product.repository.ProductDraftRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Plik nowych i zmienionych artykułów (CADBEZP) przy zapisie pliku XML faktury; przeglądarka zapisuje go w folderze
 * z ustawień sklepu. Sam plik niczego nie zapisuje w bazie: dopiero gdy pracownik potwierdzi, że program sklepu go
 * poprawnie wczytał, nowe artykuły z pliku zostają szkicami ({@link ProductDraft}), które rozstrzyga kolejny raport
 * kartoteki ({@code ProductDraftService}).
 */
@Service
public class DataEntryProductFileService {

    private final XlsWriter xlsWriter;
    private final SupplierRepository suppliers;
    private final ProductDraftRepository drafts;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public DataEntryProductFileService(XlsWriter xlsWriter, SupplierRepository suppliers,
            ProductDraftRepository drafts, StoreRepository stores, AppUserRepository users) {
        this.xlsWriter = xlsWriter;
        this.suppliers = suppliers;
        this.drafts = drafts;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DataEntryService.ExportFile file(UUID storeId, ProductFileRequest request) {
        byte[] content = xlsWriter.write("Arkusz1", ProductFile.HEADERS, ProductFile.rows(request.rows()));
        return new DataEntryService.ExportFile(
                ProductFile.fileName(supplierName(storeId, request), request.invoiceNumber()), content);
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
        if (ean == null || !ean.strip().matches("\\d{8,14}")) {
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
