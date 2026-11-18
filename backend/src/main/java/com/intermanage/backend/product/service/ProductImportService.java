package com.intermanage.backend.product.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.intermanage.backend.common.component.ChunkedUploads;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.product.dto.EanChangeResponse;
import com.intermanage.backend.product.dto.ParsedProductReport;
import com.intermanage.backend.product.dto.ProductImportJobResponse;
import com.intermanage.backend.product.dto.ProductImportOverview;
import com.intermanage.backend.product.dto.ProductImportResponse;
import com.intermanage.backend.product.dto.ProductReportRow;
import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductEan;
import com.intermanage.backend.product.entity.ProductEanChange;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.product.entity.ProductPriceChange;
import com.intermanage.backend.product.mapper.ProductImportMapper;
import com.intermanage.backend.product.repository.ArticleMergeRepository;
import com.intermanage.backend.product.repository.ProductEanChangeRepository;
import com.intermanage.backend.product.repository.ProductEanRepository;
import com.intermanage.backend.product.repository.ProductImportRepository;
import com.intermanage.backend.product.repository.ProductPriceChangeRepository;
import com.intermanage.backend.product.repository.ProductRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

import jakarta.persistence.EntityManager;

/**
 * Import codziennego raportu kartoteki produktów do wybranego sklepu (numer sklepu z nagłówka raportu musi się
 * zgadzać). Każdy sklep ma własną kartotekę, pary EAN i historię importów. Plik jest najpierw w całości czytany i
 * sprawdzany, potem w jednej transakcji scalany z kartoteką po ITM8: nowe kody są dodawane, zmienione ceny / stany /
 * rotacja / nazwy nadpisywane, a produkty, których w pliku zabrakło, zostają w bazie bez zmian. Każda para ITM8 + EAN
 * trafia na stałe do {@link ProductEan} (pod nią będzie zapisywana sprzedaż), a zmiany przypisań EAN - także korekty
 * błędnych EAN-ów z systemu sklepu - do dziennika {@link ProductEanChange}.
 * <p>
 * Import idzie w tle ({@link ProductImportJobs}). Scalanie idzie paczkami po {@value #CHUNK_SIZE} wierszy: paczka
 * wczytuje tylko swoje produkty i pary EAN, zapisuje zmiany i czyści kontekst JPA, więc raport 200 tys. artykułów
 * (pierwszy import zakłada wszystkie) nie trzyma w pamięci całej kartoteki jako encji.
 */
@Service
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    private static final int HISTORY_SIZE = 30;
    static final int CHUNK_SIZE = 2000;
    /** Klucz blokady to ten przedrostek + id sklepu: importy do różnych sklepów nie czekają na siebie. */
    static final String IMPORT_LOCK_PREFIX = "product-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final EntityManager entityManager;
    private final ProductImportJobs jobs;
    private final ProductReportParser reportParser;
    private final ProductImportMapper productImportMapper;
    private final ProductImportRepository imports;
    private final ProductRepository products;
    private final ProductEanRepository productEans;
    private final ProductEanChangeRepository eanChanges;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final ProductPriceChangeRepository priceChanges;
    private final ProductDraftService drafts;
    private final ArticleMergeRepository merges;

    public ProductImportService(TransactionTemplate transaction, EntityManager entityManager, ProductImportJobs jobs,
            ProductReportParser reportParser, ProductImportMapper productImportMapper, ProductImportRepository imports,
            ProductRepository products, ProductEanRepository productEans, ProductEanChangeRepository eanChanges,
            AppUserRepository users, StoreRepository stores, ProductPriceChangeRepository priceChanges,
            ProductDraftService drafts, ArticleMergeRepository merges) {
        this.transaction = transaction;
        this.entityManager = entityManager;
        this.jobs = jobs;
        this.merges = merges;
        this.priceChanges = priceChanges;
        this.drafts = drafts;
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

    /**
     * Sprawdza plik, kopiuje go na dysk (plik z żądania znika po odpowiedzi) i uruchamia import w tle. Błędy pliku
     * i scalania zwraca {@link #job}.
     */
    public ProductImportJobResponse startImport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null ? Path.of(file.getOriginalFilename()).getFileName().toString() : "raport.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        checkFile(storeId, fileName);

        Path temp;
        try {
            // Skoroszyt to archiwum zip, które trzeba czytać w dowolnej kolejności, więc potrzebny jest plik na dysku.
            temp = Files.createTempFile("product-import-", ".xlsx");
            file.transferTo(temp);
        } catch (IOException e) {
            log.warn("Nie udało się zapisać pliku importu", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
        return jobs.start(storeId, fileName, () -> delete(temp),
                progress -> importReport(storeId, temp, fileName, userId, progress));
    }

    /** Nazwa pliku i sklep sprawdzane przed przyjęciem pliku (także przed wysyłką w kawałkach). */
    public void checkFile(UUID storeId, String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        if (!stores.existsById(storeId)) {
            throw ApiException.notFound("Nie znaleziono sklepu");
        }
    }

    /** Import pliku przysłanego w kawałkach ({@link ChunkedUploads}); plik przechodzi na własność importu. */
    public ProductImportJobResponse startImport(UUID storeId, ChunkedUploads.Received received, UUID userId) {
        Path temp = received.file();
        String fileName = received.fileName();
        try {
            checkFile(storeId, fileName);
        } catch (ApiException e) {
            delete(temp);
            throw e;
        }
        return jobs.start(storeId, fileName, () -> delete(temp),
                progress -> importReport(storeId, temp, fileName, userId, progress));
    }

    public ProductImportJobResponse job(UUID storeId, UUID jobId) {
        return jobs.get(storeId, jobId);
    }

    private ProductImportResponse importReport(UUID storeId, Path file, String fileName, UUID userId,
            ProductImportJobs.Progress progress) {
        long start = System.nanoTime();
        ParsedProductReport report = parse(file, progress);
        long parsed = System.nanoTime();
        ProductImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId, progress));
        log.info("Import produktów do sklepu {} z {}: {} wierszy, nowe {}, zmienione {}, bez zmian {}, brak w pliku {}, "
                + "zmiana EAN {}, EAN z innego ITM8 {}, szkice dopasowane {}, usunięte {}; odczyt {} s, zapis {} s",
                storeId, fileName, result.totalRows(), result.createdCount(), result.updatedCount(),
                result.unchangedCount(), result.missingCount(), result.eanChangedCount(), result.eanMovedCount(),
                result.draftsMatchedCount(), result.draftsRemovedCount(), (parsed - start) / 1_000_000_000,
                (System.nanoTime() - parsed) / 1_000_000_000);
        return result;
    }

    private ParsedProductReport parse(Path file, ProductImportJobs.Progress progress) {
        try {
            return reportParser.parse(file, progress::reading);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
    }

    private static void delete(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Nie udało się usunąć pliku tymczasowego {}", temp, e);
        }
    }

    private ProductImportResponse merge(UUID storeId, ParsedProductReport report, String fileName, UUID userId,
            ProductImportJobs.Progress progress) {
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
        UUID importId = imports.save(new ProductImport(store, fileName, report.generatedAt(), user, report.rows().size()))
                .getId();
        entityManager.flush();

        Counts counts = new Counts();
        Map<String, UUID> reportByEan = new HashMap<>();
        List<ProductReportRow> rows = report.rows();
        progress.saving(0, rows.size());
        Stages stages = new Stages();
        int analyzedAt = 0;
        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            // Statystyki sprzed importu nie znają wierszy z tej transakcji: przy nowym sklepie planista uważa kartotekę
            // za pustą i szuka paczki skanem całej, rosnącej tabeli (200 tys. wierszy: minuty zamiast sekund).
            // Odświeżamy je, ilekroć zapisanych wierszy jest dwa razy więcej niż przy ostatnim odświeżeniu.
            if (from > 0 && from >= 2 * analyzedAt) {
                merges.analyze();
                analyzedAt = from;
            }
            // Po wyczyszczeniu kontekstu import jest tylko odwołaniem: paczka wczytuje go od nowa.
            ProductImport productImport = imports.getReferenceById(importId);
            mergeChunk(storeId, productImport, rows.subList(from, Math.min(rows.size(), from + CHUNK_SIZE)), counts,
                    reportByEan);
            entityManager.flush();
            entityManager.clear();
            progress.saving(Math.min(rows.size(), from + CHUNK_SIZE), rows.size());
        }
        stages.mark("paczki");
        progress.finishing();

        // CENA_CAD, stan i rotacja obowiązują tylko z bieżącego raportu: artykuły spoza pliku je tracą.
        products.clearMissingFromImport(storeId, importId);
        stages.mark("brak w pliku");
        int missing = Math.toIntExact(products.countByStoreIdAndLastSeenImportIdNot(storeId, importId));
        stages.mark("liczenie");

        ProductImport productImport = imports.findById(importId).orElseThrow();
        productImport.recordResult(counts.created, counts.updated, counts.unchanged, missing, counts.eanChanged,
                counts.eanMoved);
        ProductDraftService.Result draftResult = drafts.resolve(productImport, reportByEan);
        productImport.recordDrafts(draftResult.matched(), draftResult.removed());
        stages.mark("szkice");
        // Artykuł, którego zabrakło w raporcie, a jego EAN ma teraz inny artykuł, staje się jego częścią.
        merges.analyze();
        stages.mark("statystyki");
        merges.endStale(storeId);
        stages.mark("koniec połączeń");
        merges.insertNew(storeId);
        stages.mark("nowe połączenia");
        log.info("Import produktów do sklepu {}, {} wierszy, etapy: {}", storeId, rows.size(), stages);
        return productImportMapper.toResponse(productImport);
    }

    /** Jedna paczka wierszy raportu: tylko jej produkty i pary EAN (także pary jej EAN-ów u innych produktów). */
    private void mergeChunk(UUID storeId, ProductImport productImport, List<ProductReportRow> rows, Counts counts,
            Map<String, UUID> reportByEan) {
        List<String> itm8s = rows.stream().map(ProductReportRow::itm8).toList();
        Map<String, Product> productsByItm8 = new HashMap<>();
        for (Product product : products.findByStoreIdAndItm8In(storeId, itm8s)) {
            productsByItm8.put(product.getItm8(), product);
        }
        Set<String> eans = new LinkedHashSet<>();
        rows.forEach(row -> eans.add(row.ean()));
        Catalog catalog = Catalog.load(storeId, productImport.getId(), productsByItm8, eans, productEans);

        List<Product> newProducts = new ArrayList<>();
        List<ProductEan> newPairs = new ArrayList<>();
        List<ProductEanChange> changes = new ArrayList<>();
        List<ProductPriceChange> priceChangeList = new ArrayList<>();
        List<Product> rowProducts = new ArrayList<>(rows.size());

        for (ProductReportRow row : rows) {
            Product product = productsByItm8.get(row.itm8());
            if (product == null) {
                product = new Product(row, productImport);
                newProducts.add(product);
            } else {
                BigDecimal previousSalePrice = product.getSalePrice();
                if (product.seenIn(row, productImport)) {
                    counts.updated++;
                    if (!sameNumber(previousSalePrice, product.getSalePrice())) {
                        priceChangeList.add(ProductPriceChange.fromReport(product, productImport, previousSalePrice));
                    }
                } else {
                    counts.unchanged++;
                }
            }
            rowProducts.add(product);

            // Porównanie ze stanem sprzed importu: pary dodane w tym imporcie katalog pomija.
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
                counts.eanChanged += previousEan != null ? 1 : 0;
                counts.eanMoved += previousProduct != null ? 1 : 0;
            }
        }

        products.saveAll(newProducts);
        productEans.saveAll(newPairs);
        eanChanges.saveAll(changes);
        priceChanges.saveAll(priceChangeList);
        counts.created += newProducts.size();
        // Id nowych produktów są już nadane, a kontekst zaraz zostanie wyczyszczony: szkicom wystarczą id.
        for (int i = 0; i < rows.size(); i++) {
            reportByEan.put(rows.get(i).ean().replaceFirst("^0+", ""), rowProducts.get(i).getId());
        }
    }

    /** Czas etapów scalania do logu, żeby było widać, co rośnie z wielkością raportu. */
    private static final class Stages {
        private final StringBuilder text = new StringBuilder();
        private long last = System.nanoTime();

        void mark(String stage) {
            long now = System.nanoTime();
            text.append(text.isEmpty() ? "" : ", ").append(stage).append(' ').append((now - last) / 1_000_000).append(" ms");
            last = now;
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static final class Counts {
        int created;
        int updated;
        int unchanged;
        int eanChanged;
        int eanMoved;
    }

    /**
     * Kartoteka paczki sprzed importu: jej produkty po ITM8 i istniejące pary EAN (pary jej produktów i pary jej
     * EAN-ów u innych produktów). Pary są porządkowane według ostatniego wystąpienia, przy remisie wygrywa nowsza.
     */
    private record Catalog(Map<Product, List<ProductEan>> pairsByProduct, Map<String, List<ProductEan>> pairsByEan) {

        static Catalog load(UUID storeId, UUID importId, Map<String, Product> productsByItm8, Set<String> eans,
                ProductEanRepository productEans) {
            Map<UUID, ProductEan> pairs = new HashMap<>();
            if (!productsByItm8.isEmpty()) {
                productEans.findByProductIn(productsByItm8.values()).forEach(pair -> pairs.put(pair.getId(), pair));
            }
            productEans.findOfEansBefore(storeId, eans, importId).forEach(pair -> pairs.put(pair.getId(), pair));

            Map<Product, List<ProductEan>> byProduct = new HashMap<>();
            Map<String, List<ProductEan>> byEan = new HashMap<>();
            for (ProductEan pair : pairs.values()) {
                // Produkty są już w kontekście JPA, więc getProduct() zwraca tę samą instancję co productsByItm8.
                byProduct.computeIfAbsent(pair.getProduct(), p -> new ArrayList<>()).add(pair);
                byEan.computeIfAbsent(pair.getEan(), e -> new ArrayList<>()).add(pair);
            }
            return new Catalog(byProduct, byEan);
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

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
