package com.intermanage.backend.dataentry.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.dataentry.component.PriceControlFile;
import com.intermanage.backend.dataentry.dto.CatalogProduct;
import com.intermanage.backend.dataentry.dto.PriceControlFiles;
import com.intermanage.backend.dataentry.dto.PriceControlSeenRequest;
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
 * do historii od razu (przeglądarka zapisuje go potem w folderze plików PROTO), więc da się go zapisać ponownie. Do czasu,
 * aż program sklepu go wczyta (końcówka .TraiteN w folderze), jest na liście niewgranych.
 */
@Service
public class DataEntryPriceControlService {

    /** Tyle ostatnich wgranych plików pokazuje lista (niewgrane są zawsze wszystkie). */
    static final int HISTORY_SIZE = 20;
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
        // Dostawca nie jest wymagany: bez niego (albo bez hasła na liście dostawców) w nazwie pliku stoi login, jak
        // w plikach etykiet i zamówień (ETIQUETTE_…_<login>_<login>).
        Supplier supplier = supplier(storeId, request);
        String keyword = supplier == null || supplier.getKeyword() == null ? "" : supplier.getKeyword().trim();
        if (ProtoFile.namePart(keyword).isEmpty()) {
            keyword = ProtoFile.loginPart(username);
        }
        List<String> lines = PriceControlFile.lines(request.rows().stream()
                .map(row -> new PriceControlFile.Line(row.ean(), row.salePrice()))
                .toList());
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Żaden artykuł ze zmienioną ceną nie ma EAN-u i ceny sprzedaży");
        }
        DataEntryPriceControlFile file = files.save(new DataEntryPriceControlFile(storeId,
                supplier == null ? null : supplier.getId(), supplier == null ? null : supplier.getCode(), keyword,
                supplier == null ? null : supplier.getName(), request.invoiceNumber(),
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

    /** Wszystkie niewgrane pliki sklepu i ostatnie wgrane; usunięte z listy nie wracają. */
    @Transactional(readOnly = true)
    public PriceControlFiles history(UUID storeId) {
        return new PriceControlFiles(
                files.findPending(storeId).stream().map(mapper::toItem).toList(),
                files.findLoaded(storeId, PageRequest.of(0, HISTORY_SIZE)).stream().map(mapper::toItem).toList());
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID storeId) {
        return files.countPending(storeId);
    }

    /**
     * Przeglądarka znalazła w folderze plików PROTO pliki z końcówką .TraiteN: program sklepu je wczytał. Niewgrane
     * stają się wgrane, wgrane dostają wyższą liczbę przetworzeń; pliki innego sklepu i usunięte są pomijane.
     */
    @Transactional
    public PriceControlFiles seen(UUID storeId, PriceControlSeenRequest request, UUID userId) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        request.files().forEach(seen -> counts.merge(seen.id(), seen.processedCount(), Math::max));
        if (!counts.isEmpty()) {
            AppUser by = userId != null ? users.getReferenceById(userId) : null;
            files.findByStoreIdAndIdIn(storeId, counts.keySet()).stream()
                    .filter(file -> !file.isDeleted())
                    .forEach(file -> file.seenProcessed(counts.get(file.getId()), by));
        }
        return history(storeId);
    }

    /** Ręczne oznaczenie: plik wczytano, a przeglądarka nie widzi folderu (inny komputer, plik przeniesiony). */
    @Transactional
    public PriceControlFiles markLoaded(UUID storeId, UUID id, UUID userId) {
        DataEntryPriceControlFile file = active(storeId, id);
        if (file.isLoaded()) {
            throw ApiException.conflict("Ta zmiana cen jest już wgrana");
        }
        file.markLoaded(userId != null ? users.getReferenceById(userId) : null);
        return history(storeId);
    }

    /** Cofnięcie oznaczenia: plik wraca do niewgranych (sprawdzenie folderu znów może go potwierdzić). */
    @Transactional
    public PriceControlFiles unmarkLoaded(UUID storeId, UUID id) {
        active(storeId, id).unmarkLoaded();
        return history(storeId);
    }

    /** Usunięcie z listy to znacznik; plik zostaje w bazie i w wyszukiwaniu historii cen artykułu. */
    @Transactional
    public PriceControlFiles delete(UUID storeId, UUID id, UUID userId) {
        active(storeId, id).delete(userId != null ? users.getReferenceById(userId) : null);
        return history(storeId);
    }

    @Transactional
    public PriceControlFiles restore(UUID storeId, UUID id) {
        file(storeId, id).restore();
        return history(storeId);
    }

    /**
     * Ponowny zapis: ta sama treść pod nazwą z bieżącą godziną, bo program sklepu pomija nazwę, którą już kiedyś widział.
     * Lista pilnuje od teraz nowej nazwy.
     */
    @Transactional
    public DataEntryPriceControlFile resave(UUID storeId, UUID id) {
        DataEntryPriceControlFile file = active(storeId, id);
        file.rename(PriceControlFile.renewed(file.getFileName()));
        return file;
    }

    @Transactional(readOnly = true)
    public DataEntryPriceControlFile file(UUID storeId, UUID id) {
        return files.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zmiany cen"));
    }

    private DataEntryPriceControlFile active(UUID storeId, UUID id) {
        DataEntryPriceControlFile file = file(storeId, id);
        if (file.isDeleted()) {
            throw ApiException.notFound("Nie znaleziono zmiany cen");
        }
        return file;
    }

    /** Dostawca wybrany w tabeli, a bez niego dostawca z listy sklepu o NIP-ie sprzedawcy; brak = {@code null}. */
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
                .orElse(null);
    }
}
