package com.intermanage.backend.dataentry.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.XlsWriter;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.ProductFile;
import com.intermanage.backend.dataentry.dto.ProductFileRequest;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Plik nowych i zmienionych artykułów (CADBEZP) przy zapisie pliku XML faktury; przeglądarka zapisuje go w folderze
 * z ustawień sklepu. Niczego nie zapisuje w bazie.
 */
@Service
public class DataEntryProductFileService {

    private final XlsWriter xlsWriter;
    private final SupplierRepository suppliers;

    public DataEntryProductFileService(XlsWriter xlsWriter, SupplierRepository suppliers) {
        this.xlsWriter = xlsWriter;
        this.suppliers = suppliers;
    }

    @Transactional(readOnly = true)
    public DataEntryService.ExportFile file(UUID storeId, ProductFileRequest request) {
        byte[] content = xlsWriter.write("Arkusz1", ProductFile.HEADERS, ProductFile.rows(request.rows()));
        return new DataEntryService.ExportFile(
                ProductFile.fileName(supplierName(storeId, request), request.invoiceNumber()), content);
    }

    /** Nazwa z listy dostawców sklepu (krótka, jak w programie sklepu), bez niej nazwa sprzedawcy z faktury. */
    private String supplierName(UUID storeId, ProductFileRequest request) {
        if (request.supplierId() != null) {
            return suppliers.findById(request.supplierId())
                    .filter(supplier -> supplier.getStore().getId().equals(storeId))
                    .map(Supplier::getName)
                    .orElseThrow(() -> ApiException.badRequest("Nie znaleziono dostawcy w tym sklepie"));
        }
        String nip = DataEntryLinkService.digits(request.sellerNip());
        return nip.isEmpty() ? request.sellerName() : suppliers.findByStore(storeId).stream()
                .filter(supplier -> nip.equals(DataEntryLinkService.digits(supplier.getNip())))
                .map(Supplier::getName).findFirst().orElse(request.sellerName());
    }
}
