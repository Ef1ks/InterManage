package com.intermanage.backend.product.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
import com.intermanage.backend.product.component.DepositKindReportParser;
import com.intermanage.backend.product.dto.DepositKindImportOverview;
import com.intermanage.backend.product.dto.DepositKindImportResponse;
import com.intermanage.backend.product.dto.ParsedDepositKindReport;
import com.intermanage.backend.product.entity.DepositKind;
import com.intermanage.backend.product.entity.DepositKindImport;
import com.intermanage.backend.product.mapper.DepositKindMapper;
import com.intermanage.backend.product.repository.DepositKindImportRepository;
import com.intermanage.backend.product.repository.DepositKindRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Lista opakowań zwrotnych sklepu (PDF z systemu sklepu): kod kaucji z kartoteki i multikodów → nazwa i wartość.
 * Scalanie po kodzie; kody, których zabrakło w pliku, zostają. Z niej kolektor drukuje kaucję na cenówce.
 */
@Service
public class DepositKindService {

    private static final Logger log = LoggerFactory.getLogger(DepositKindService.class);

    private static final int HISTORY_SIZE = 30;
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final DepositKindReportParser reportParser;
    private final DepositKindMapper mapper;
    private final DepositKindImportRepository imports;
    private final DepositKindRepository kinds;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public DepositKindService(TransactionTemplate transaction, DepositKindReportParser reportParser,
            DepositKindMapper mapper, DepositKindImportRepository imports, DepositKindRepository kinds,
            StoreRepository stores, AppUserRepository users) {
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.mapper = mapper;
        this.imports = imports;
        this.kinds = kinds;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DepositKindImportOverview imports(UUID storeId) {
        return new DepositKindImportOverview(
                kinds.findAllByStore(storeId).stream().map(mapper::toResponse).toList(),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(mapper::toResponse).toList());
    }

    public DepositKindImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "opakowania.pdf";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.badRequest("Listę opakowań zwrotnych wgrywa się jako PDF z systemu sklepu.");
        }
        ParsedDepositKindReport report = parse(file);
        DepositKindImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import opakowań zwrotnych do sklepu {} z {}: {} pozycji, nowe {}, zmienione {}, brak w pliku {}",
                storeId, fileName, result.totalRows(), result.createdCount(), result.updatedCount(),
                result.missingCount());
        return result;
    }

    private ParsedDepositKindReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("deposit-kinds-import-", ".pdf");
            file.transferTo(temp);
            return reportParser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku listy opakowań zwrotnych", e);
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

    private DepositKindImportResponse merge(UUID storeId, ParsedDepositKindReport report, String fileName,
            UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (!store.hasNumber(report.storeNumber())) {
            throw ApiException.conflict("To lista sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Ta lista opakowań zwrotnych (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starsza niż ostatnio wgrana (" + REPORT_DATE_FORMAT.format(newest)
                    + "). Wgraj najnowszą.");
        }
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        DepositKindImport depositImport = imports.save(
                new DepositKindImport(store, fileName, report.generatedAt(), user, report.rows().size()));

        Map<Short, DepositKind> known = new HashMap<>();
        kinds.findAllByStore(storeId).forEach(kind -> known.put(kind.getCode(), kind));
        int created = 0;
        int updated = 0;
        for (ParsedDepositKindReport.Row row : report.rows()) {
            DepositKind kind = known.remove(row.code());
            boolean isNew = kind == null;
            if (isNew) {
                kind = new DepositKind(store, row.code(), depositImport);
            }
            boolean changed = kind.update(row.name(), row.vatCode(), row.value(), row.kind(), depositImport);
            if (isNew) {
                kinds.save(kind);
                created++;
            } else if (changed) {
                updated++;
            }
        }
        // W known zostały kody, których nie było w pliku: zostają.
        depositImport.recordResult(created, updated, known.size());
        return mapper.toResponse(depositImport);
    }
}
