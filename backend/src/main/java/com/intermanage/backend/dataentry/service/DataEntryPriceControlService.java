package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlFile;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.PriceControlFileItem;
import com.intermanage.backend.dataentry.dto.PriceControlRequest;
import com.intermanage.backend.dataentry.entity.DataEntryPriceControlFile;
import com.intermanage.backend.dataentry.mapper.PriceControlFileMapper;
import com.intermanage.backend.dataentry.repository.DataEntryPriceControlFileRepository;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.product.service.PriceMarkdownService;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Plik CTRLPRIX przy zapisie pliku XML faktury: artykuły z kartoteki ze zmienioną w tabeli ceną sprzedaży. Plik trafia
 * do historii od razu (przeglądarka zapisuje go potem w folderze plików PROTO), więc da się go zapisać ponownie.
 */
@Service
public class DataEntryPriceControlService {

    static final int HISTORY_SIZE = 10;
    /** {@link DataEntryService#lookup} bierze najwyżej 500 kodów naraz. */
    private static final int LOOKUP_BATCH = 500;

    private final DataEntryPriceControlFileRepository files;
    private final PriceControlFileMapper mapper;
    private final SupplierRepository suppliers;
    private final AppUserRepository users;
    private final DataEntryService dataEntry;
    private final ProductRepository products;
    private final PriceMarkdownService markdowns;

    public DataEntryPriceControlService(DataEntryPriceControlFileRepository files, PriceControlFileMapper mapper,
            SupplierRepository suppliers, AppUserRepository users, DataEntryService dataEntry,
            ProductRepository products, PriceMarkdownService markdowns) {
        this.files = files;
        this.mapper = mapper;
        this.suppliers = suppliers;
        this.users = users;
        this.dataEntry = dataEntry;
        this.products = products;
        this.markdowns = markdowns;
    }

    @Transactional
    public DataEntryPriceControlFile generate(UUID storeId, PriceControlRequest request, UUID userId,
            String username) {
        Supplier supplier = supplier(storeId, request);
        String keyword = supplier.getKeyword() == null ? "" : supplier.getKeyword().trim();
        if (ProtoFile.namePart(keyword).isEmpty()) {
            throw ApiException.badRequest("Dostawca " + supplier.getName()
                    + " nie ma hasła na liście dostawców, a zmiana cen go wymaga");
        }
        List<String> lines = PriceControlFile.lines(request.rows().stream()
                .map(row -> new PriceControlFile.Line(row.ean(), row.salePrice()))
                .toList());
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Żaden artykuł ze zmienioną ceną nie ma EAN-u i ceny sprzedaży");
        }
        DataEntryPriceControlFile file = files.save(new DataEntryPriceControlFile(storeId, supplier.getId(),
                supplier.getCode(), keyword, supplier.getName(), request.invoiceNumber(),
                PriceControlFile.fileName(keyword, username), lines.size(), PriceControlFile.content(lines),
                userId != null ? users.getReferenceById(userId) : null));
        markdowns.addPriceChanges(storeId, changedProducts(storeId, request.rows()), file.getId(), userId);
        return file;
    }

    /**
     * Artykuły z kartoteki z nową ceną: każdy z pliku CTRLPRIX trafia na listę cenówek (zmiana ceny), z której powstaje
     * jeden plik ETIQUETTE ze wszystkimi zmianami dnia. Kod spoza kartoteki (szkic, nieznany EAN) się nie liczy.
     */
    private Map<Product, BigDecimal> changedProducts(UUID storeId, List<PriceControlRequest.Row> rows) {
        Map<String, BigDecimal> priceByKey = new LinkedHashMap<>();
        for (PriceControlRequest.Row row : rows) {
            String key = DataEntryService.key(DataEntryService.ean(row.ean()));
            if (key != null && row.salePrice() != null && row.salePrice().signum() > 0) {
                priceByKey.put(key, row.salePrice());
            }
        }
        Map<String, BigDecimal> priceByItm8 = new LinkedHashMap<>();
        List<String> keys = List.copyOf(priceByKey.keySet());
        for (int from = 0; from < keys.size(); from += LOOKUP_BATCH) {
            for (CatalogProduct product : dataEntry.lookup(storeId, keys.subList(from, Math.min(keys.size(), from + LOOKUP_BATCH)))) {
                if (!product.draft() && product.itm8() != null && priceByKey.containsKey(product.ean())) {
                    priceByItm8.put(product.itm8(), priceByKey.get(product.ean()));
                }
            }
        }
        Map<Product, BigDecimal> result = new LinkedHashMap<>();
        if (!priceByItm8.isEmpty()) {
            products.findByStoreIdAndItm8In(storeId, priceByItm8.keySet())
                    .forEach(product -> result.put(product, priceByItm8.get(product.getItm8())));
        }
        return result;
    }

    /** 10 ostatnich plików sklepu, od najnowszego. */
    @Transactional(readOnly = true)
    public List<PriceControlFileItem> history(UUID storeId) {
        return files.findLatest(storeId, PageRequest.of(0, HISTORY_SIZE)).stream().map(mapper::toItem).toList();
    }

    @Transactional(readOnly = true)
    public DataEntryPriceControlFile file(UUID storeId, UUID id) {
        return files.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zmiany cen"));
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
