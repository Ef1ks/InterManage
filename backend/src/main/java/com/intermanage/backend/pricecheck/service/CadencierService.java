package com.intermanage.backend.pricecheck.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

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
import com.intermanage.backend.pricecheck.component.CadencierReportParser;
import com.intermanage.backend.pricecheck.dto.CadencierImportResponse;
import com.intermanage.backend.pricecheck.dto.CadencierOverview;
import com.intermanage.backend.pricecheck.dto.ParsedCadencierReport;
import com.intermanage.backend.pricecheck.entity.CadencierImport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.CadencierImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Lista artykułów obecnych w cadencier (formularzu zamówień do bazy), wgrywana w Ustawieniach sklepu. Lista z pliku
 * jest jedynym źródłem prawdy: każdy import zastępuje ją w całości, a artykuły, których w nim nie ma, znikają.
 */
@Service
public class CadencierService {

    private static final Logger log = LoggerFactory.getLogger(CadencierService.class);

    private static final int HISTORY_SIZE = 10;
    private static final String IMPORT_LOCK_PREFIX = "cadencier-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final CadencierReportParser parser;
    private final CadencierImportRepository imports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceCheckMapper mapper;

    public CadencierService(TransactionTemplate transaction, CadencierReportParser parser,
            CadencierImportRepository imports, StoreRepository stores, AppUserRepository users,
            PriceCheckMapper mapper) {
        this.transaction = transaction;
        this.parser = parser;
        this.imports = imports;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public CadencierOverview overview(UUID storeId) {
        var latest = imports.findLatest(storeId, Limit.of(HISTORY_SIZE));
        return new CadencierOverview(latest.isEmpty() ? 0 : latest.getFirst().getTotalRows(),
                latest.stream().map(mapper::toResponse).toList());
    }

    public CadencierImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "cadencier.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        ParsedCadencierReport report = parse(file);
        CadencierImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import cadencier do sklepu {} z {}: {} artykułów, nowe {}, ubyło {}", storeId, fileName,
                result.totalRows(), result.addedCount(), result.removedCount());
        return result;
    }

    private ParsedCadencierReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("cadencier-import-", ".xlsx");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku cadencier", e);
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

    private CadencierImportResponse merge(UUID storeId, ParsedCadencierReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To lista sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        imports.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        CadencierImport previous = imports.findNewest(storeId).orElse(null);
        if (previous != null && previous.getReportGeneratedAt() != null && report.generatedAt() != null
                && report.generatedAt().isBefore(previous.getReportGeneratedAt())) {
            throw ApiException.conflict("Ta lista (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starsza niż ostatnio wgrana ("
                    + REPORT_DATE_FORMAT.format(previous.getReportGeneratedAt()) + "). Wgraj najnowszą.");
        }

        CadencierImport saved = imports.saveAndFlush(new CadencierImport(store, fileName, report.generatedAt(),
                userId != null ? users.getReferenceById(userId) : null, report.itm8s().size()));
        CadencierImportRepository.MergeCounts counts = imports.merge(storeId, saved.getId(),
                report.itm8s().toArray(String[]::new),
                report.eans().toArray(String[]::new));
        saved.recordResult((int) counts.getAdded(), (int) counts.getRemoved());
        return mapper.toResponse(saved);
    }
}
