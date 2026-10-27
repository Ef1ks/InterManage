package com.intermanage.backend.product.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.product.dto.EanChangeResponse;
import com.intermanage.backend.product.dto.ParsedProductReport;
import com.intermanage.backend.product.dto.ProductImportOverview;
import com.intermanage.backend.product.dto.ProductImportResponse;
import com.intermanage.backend.product.dto.ProductReportRow;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductEan;
import com.intermanage.backend.product.entity.ProductEanChange;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.mapper.ProductImportMapper;
import com.intermanage.backend.product.repository.ProductEanChangeRepository;
import com.intermanage.backend.product.repository.ProductEanRepository;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Import codziennego raportu kartoteki produktów do wybranego sklepu (numer sklepu z nagłówka raportu musi się
 * zgadzać). Każdy sklep ma własną kartotekę, pary EAN i historię importów. Plik jest najpierw w całości czytany i sprawdzany, potem w jednej
 * transakcji scalany z kartoteką po ITM8: nowe kody są dodawane, zmienione ceny / stany / rotacja / nazwy
 * nadpisywane, a produkty, których w pliku zabrakło, zostają w bazie bez zmian. Każda para ITM8 + EAN trafia na stałe
 * do {@link ProductEan} (pod nią będzie zapisywana sprzedaż), a zmiany przypisań EAN - także korekty błędnych EAN-ów
 * z systemu sklepu - do dziennika {@link ProductEanChange}.
 * <p>
 * Cała kartoteka (ok. 72 tys. produktów i ich par EAN) jest wczytywana do kontekstu JPA i porównywana w pamięci;
 * Hibernate zapisuje zmiany paczkami ({@code hibernate.jdbc.batch_size}).
 */
@Service
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    private static final int HISTORY_SIZE = 30;
    /** Klucz blokady to ten przedrostek + id sklepu: importy do różnych sklepów nie czekają na siebie. */
    private static final String IMPORT_LOCK_PREFIX = "product-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final ProductReportParser reportParser;
    private final ProductImportMapper productImportMapper;
    private final ProductImportRepository imports;
    private final ProductRepository products;
    private final ProductEanRepository productEans;
    private final ProductEanChangeRepository eanChanges;
    private final AppUserRepository users;
    private final StoreRepository stores;

    public ProductImportService(TransactionTemplate transaction, ProductReportParser reportParser,
            ProductImportMapper productImportMapper, ProductImportRepository imports, ProductRepository products,
            ProductEanRepository productEans, ProductEanChangeRepository eanChanges, AppUserRepository users,
            StoreRepository stores) {
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.productImportMapper = productImportMapper;
        this.imports = imports;
        this.products = products;
        this.productEans = productEans;
        this.eanChanges = eanChanges;
        this.users = users;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public List<EanChangeResponse> eanChanges(UUID storeId, UUID importId) {
        if (!imports.existsByIdAndStoreId(importId, storeId)) {
            throw ApiException.notFound("Nie znaleziono importu");
        }
        return eanChanges.findByImportId(importId).stream()
                .map(productImportMapper::toResponse)
                .sorted(Comparator.comparing(EanChangeResponse::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(EanChangeResponse::itm8))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductImportOverview overview(UUID storeId) {
        List<ProductImportResponse> latest = imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream()
                .map(productImportMapper::toResponse)
                .toList();
        return new ProductImportOverview(products.countByStoreId(storeId), latest);
    }

    public ProductImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null ? Path.of(file.getOriginalFilename()).getFileName().toString() : "raport.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }

        ParsedProductReport report = parse(file);
        ProductImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import produktów do sklepu {} z {}: {} wierszy, nowe {}, zmienione {}, bez zmian {}, brak w pliku {}, "
                + "zmiana EAN {}, EAN z innego ITM8 {}", storeId, fileName, result.totalRows(), result.createdCount(),
                result.updatedCount(), result.unchangedCount(), result.missingCount(), result.eanChangedCount(),
                result.eanMovedCount());
        return result;
    }

    private ParsedProductReport parse(MultipartFile file) {
        Path temp = null;
        try {
            // Skoroszyt to archiwum zip, które trzeba czytać w dowolnej kolejności, więc potrzebny jest plik na dysku.
            temp = Files.createTempFile("product-import-", ".xlsx");
            file.transferTo(temp);
            return reportParser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Nie udało się usunąć pliku tymczasowego {}", temp, e);
                }
            }
        }
    }

    private ProductImportResponse merge(UUID storeId, ParsedProductReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        products.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Ten raport (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starszy niż ostatnio wgrany (" + REPORT_DATE_FORMAT.format(newest)
                    + "). Wgraj najnowszy raport.");
        }

        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        ProductImport productImport = imports.save(
                new ProductImport(store, fileName, report.generatedAt(), user, report.rows().size()));

        Catalog catalog = Catalog.load(store, products, productEans);
        List<Product> newProducts = new ArrayList<>();
        List<ProductEan> newPairs = new ArrayList<>();
        List<ProductEanChange> changes = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;
        int eanChanged = 0;
        int eanMoved = 0;

        for (ProductReportRow row : report.rows()) {
            Product product = catalog.productsByItm8.get(row.itm8());
            if (product == null) {
                product = new Product(row, productImport);
                newProducts.add(product);
            } else if (product.seenIn(row, productImport)) {
                updated++;
            } else {
                unchanged++;
            }

            // Porównanie ze stanem sprzed importu: nowe pary trafiają do katalogu dopiero po pętli.
            ProductEan latestOwn = catalog.latestPairOf(product);
            ProductEan ownPair = catalog.pair(product, row.ean());
            String previousEan = latestOwn != null && !latestOwn.getEan().equals(row.ean()) ? latestOwn.getEan() : null;
            Product previousProduct = null;
            if (ownPair == null) {
                ProductEan latestOther = catalog.latestPairOfEanOutside(row.ean(), product);
                previousProduct = latestOther != null ? latestOther.getProduct() : null;
                newPairs.add(new ProductEan(product, row.ean(), productImport));
            } else {
                ownPair.seenIn(productImport);
            }

            if (previousEan != null || previousProduct != null) {
                changes.add(new ProductEanChange(productImport, product, row.ean(), previousEan, previousProduct));
                eanChanged += previousEan != null ? 1 : 0;
                eanMoved += previousProduct != null ? 1 : 0;
            }
        }

        products.saveAll(newProducts);
        productEans.saveAll(newPairs);
        eanChanges.saveAll(changes);
        // Zapytanie wymusza zapis zmian z pętli, więc liczy już po aktualizacji last_seen_import_id.
        int missing = Math.toIntExact(products.countByStoreAndLastSeenImportNot(store, productImport));

        productImport.recordResult(newProducts.size(), updated, unchanged, missing, eanChanged, eanMoved);
        return productImportMapper.toResponse(productImport);
    }

    /**
     * Kartoteka sprzed importu: produkty po ITM8 i istniejące pary EAN. Pary są porządkowane według ostatniego
     * wystąpienia, przy remisie wygrywa nowsza para.
     */
    private record Catalog(
            Map<String, Product> productsByItm8,
            Map<Product, List<ProductEan>> pairsByProduct,
            Map<String, List<ProductEan>> pairsByEan) {

        static Catalog load(Store store, ProductRepository products, ProductEanRepository productEans) {
            Map<String, Product> byItm8 = new HashMap<>();
            for (Product product : products.findByStore(store)) {
                byItm8.put(product.getItm8(), product);
            }
            Map<Product, List<ProductEan>> byProduct = new HashMap<>();
            Map<String, List<ProductEan>> byEan = new HashMap<>();
            for (ProductEan pair : productEans.findByStore(store)) {
                // Produkty są już w kontekście JPA, więc getProduct() zwraca tę samą instancję co byItm8.
                byProduct.computeIfAbsent(pair.getProduct(), p -> new ArrayList<>()).add(pair);
                byEan.computeIfAbsent(pair.getEan(), e -> new ArrayList<>()).add(pair);
            }
            return new Catalog(byItm8, byProduct, byEan);
        }

        ProductEan pair(Product product, String ean) {
            for (ProductEan pair : pairsByProduct.getOrDefault(product, List.of())) {
                if (pair.getEan().equals(ean)) {
                    return pair;
                }
            }
            return null;
        }

        ProductEan latestPairOf(Product product) {
            return latest(pairsByProduct.getOrDefault(product, List.of()), null);
        }

        ProductEan latestPairOfEanOutside(String ean, Product product) {
            return latest(pairsByEan.getOrDefault(ean, List.of()), product);
        }

        private static ProductEan latest(List<ProductEan> pairs, Product excluded) {
            ProductEan latest = null;
            for (ProductEan pair : pairs) {
                if (pair.getProduct() != excluded && (latest == null || pair.seenLaterThan(latest))) {
                    latest = pair;
                }
            }
            return latest;
        }
    }
}
