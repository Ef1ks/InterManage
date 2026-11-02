package com.intermanage.backend.product.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.product.component.ProductCodeReportParser;
import com.intermanage.backend.product.dto.ParsedProductCodeReport;
import com.intermanage.backend.product.dto.ProductCodeImportOverview;
import com.intermanage.backend.product.dto.ProductCodeImportResponse;
import com.intermanage.backend.product.dto.ProductCodeReportRow;
import com.intermanage.backend.product.entity.ProductCodeImport;
import com.intermanage.backend.product.mapper.ProductImportMapper;
import com.intermanage.backend.product.repository.ProductCodeImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Import multikodów: wszystkie EAN-y przypisane w chwili wydruku do każdego ITM8 sklepu. Pary ITM8 + EAN są scalane:
 * nowe dopisywane, znane aktualizowane, a te, których zabrakło w pliku, zostają jako historia przypisań (obowiązują
 * pary z najnowszego importu).
 */
@Service
public class ProductCodeImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductCodeImportService.class);

    private static final int HISTORY_SIZE = 30;
    /** Pary zapisywane jednym zapytaniem; plik ma ok. 86 tys. wierszy. */
    private static final int BATCH_SIZE = 10_000;
    private static final String IMPORT_LOCK_PREFIX = "product-code-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final ProductCodeReportParser parser;
    private final ProductCodeImportRepository imports;
    private final ProductImportMapper mapper;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public ProductCodeImportService(TransactionTemplate transaction, ProductCodeReportParser parser,
            ProductCodeImportRepository imports, ProductImportMapper mapper, StoreRepository stores,
            AppUserRepository users) {
        this.transaction = transaction;
        this.parser = parser;
        this.imports = imports;
        this.mapper = mapper;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public ProductCodeImportOverview imports(UUID storeId) {
        ProductCodeImportRepository.CurrentCounts current = imports.countCurrent(storeId);
        return new ProductCodeImportOverview(current.getCodes(), current.getArticles(),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(mapper::toResponse).toList());
    }

    public ProductCodeImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "multikody.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        ParsedProductCodeReport report = parse(file);
        ProductCodeImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import multikodów do sklepu {} z {}: {} par, nowe {}, zmienione {}, brak w pliku {}", storeId,
                fileName, result.totalRows(), result.createdCount(), result.updatedCount(), result.missingCount());
        return result;
    }

    private ParsedProductCodeReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("product-code-import-", ".xlsx");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku multikodów", e);
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

    private ProductCodeImportResponse merge(UUID storeId, ParsedProductCodeReport report, String fileName,
            UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        imports.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Te multikody (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") są starsze niż ostatnio wgrane (" + REPORT_DATE_FORMAT.format(newest)
                    + "). Wgraj najnowsze.");
        }

        ProductCodeImport saved = imports.saveAndFlush(new ProductCodeImport(store, fileName, report.generatedAt(),
                userId != null ? users.getReferenceById(userId) : null, report.rows().size()));
        long created = 0;
        long updated = 0;
        for (int from = 0; from < report.rows().size(); from += BATCH_SIZE) {
            List<ProductCodeReportRow> batch = report.rows().subList(from,
                    Math.min(from + BATCH_SIZE, report.rows().size()));
            ProductCodeImportRepository.MergeCounts counts = imports.merge(storeId, saved.getId(),
                    strings(batch, ProductCodeReportRow::itm8),
                    strings(batch, ProductCodeReportRow::ean),
                    strings(batch, ProductCodeReportRow::name),
                    batch.stream().map(ProductCodeReportRow::prio).toArray(Boolean[]::new),
                    strings(batch, ProductCodeReportRow::saleUnit),
                    decimals(batch, ProductCodeReportRow::salePack),
                    decimals(batch, ProductCodeReportRow::priceFactor),
                    decimals(batch, ProductCodeReportRow::salePrice),
                    batch.stream().map(ProductCodeReportRow::removalDate).toArray(LocalDate[]::new),
                    batch.stream().map(ProductCodeReportRow::depositCode).toArray(Short[]::new));
            created += counts.getCreated();
            updated += counts.getUpdated();
        }
        imports.retireMissing(storeId, saved.getId());
        long missing = imports.countMissing(storeId, saved.getId());
        saved.recordResult((int) created, (int) updated, (int) missing);
        return mapper.toResponse(saved);
    }

    private static String[] strings(List<ProductCodeReportRow> rows, Function<ProductCodeReportRow, String> value) {
        return rows.stream().map(value).toArray(String[]::new);
    }

    private static BigDecimal[] decimals(List<ProductCodeReportRow> rows,
            Function<ProductCodeReportRow, BigDecimal> value) {
        return rows.stream().map(value).toArray(BigDecimal[]::new);
    }
}
