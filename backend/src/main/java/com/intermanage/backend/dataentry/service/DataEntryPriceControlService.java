package com.intermanage.backend.dataentry.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlFile;
import com.intermanage.backend.dataentry.dto.PriceControlFileItem;
import com.intermanage.backend.dataentry.dto.PriceControlRequest;
import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;
import com.intermanage.backend.dataentry.mapper.PriceControlFileMapper;
import com.intermanage.backend.dataentry.repository.DataEntryPriceControlFileRepository;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Plik CTRLPRIX przy zapisie pliku XML faktury: artykuły z kartoteki ze zmienioną w tabeli ceną sprzedaży. Plik trafia
 * do historii od razu (przeglądarka zapisuje go potem w folderze plików PROTO), więc da się go zapisać ponownie.
 */
@Service
public class DataEntryPriceControlService {

    static final int HISTORY_SIZE = 10;

    private final DataEntryPriceControlFileRepository files;
    private final PriceControlFileMapper mapper;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;

    public DataEntryPriceControlService(DataEntryPriceControlFileRepository files, PriceControlFileMapper mapper,
            SupplierRepository suppliers, AppUserRepository users) {
        this.files = files;
        this.mapper = mapper;
        this.suppliers = suppliers;
        this.users = users;
    }

    @Transactional
    public DataEntryPriceControlFile generate(UUID storeId, PriceControlRequest request, UUID userId,
            String username) {
        Supplier supplier = supplier(storeId, request);
        String keyword = supplier.getKeyword() == null ? "" : supplier.getKeyword().trim();
        if (keyword.isEmpty()) {
            throw ApiException.badRequest("Dostawca " + supplier.getName()
                    + " nie ma hasła na liście dostawców, a plik CTRLPRIX ma je w nazwie");
        }
        List<String> lines = PriceControlFile.lines(request.rows().stream()
                .map(row -> new PriceControlFile.Line(row.ean(), row.salePrice()))
                .toList());
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Żaden artykuł ze zmienioną ceną nie ma EAN-u i ceny sprzedaży");
        }
        return files.save(new DataEntryPriceControlFile(storeId, supplier.getId(), supplier.getCode(), keyword,
                supplier.getName(), request.invoiceNumber(), PriceControlFile.fileName(keyword, username), lines.size(),
                PriceControlFile.content(lines), userId != null ? users.getReferenceById(userId) : null));
    }

    /** 10 ostatnich plików sklepu, od najnowszego. */
    @Transactional(readOnly = true)
    public List<PriceControlFileItem> history(UUID storeId) {
        return files.findLatest(storeId, PageRequest.of(0, HISTORY_SIZE)).stream().map(mapper::toItem).toList();
    }

    @Transactional(readOnly = true)
    public DataEntryPriceControlFile file(UUID storeId, UUID id) {
        return files.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku CTRLPRIX"));
    }

    /** Dostawca wybrany w tabeli, a bez niego dostawca z listy sklepu o NIP-ie sprzedawcy. */
    private Supplier supplier(UUID storeId, PriceControlRequest request) {
        if (request.supplierId() != null) {
            return suppliers.findById(request.supplierId())
                    .filter(supplier -> supplier.getStore().getId().equals(storeId))
                    .orElseThrow(() -> ApiException.badRequest("Nie znaleziono dostawcy w tym sklepie"));
        }
        String nip = DataEntryLinkService.digits(request.sellerNip());
        return suppliers.findByStore(storeId).stream()
                .filter(supplier -> !nip.isEmpty() && nip.equals(DataEntryLinkService.digits(supplier.getNip())))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "Dostawcy nie ma na liście dostawców sklepu: wybierz go w „Zmień dostawcę”"));
    }
}
