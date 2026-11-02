package com.intermanage.backend.promotion.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.intermanage.backend.promotion.component.PromotionReportParser;
import com.intermanage.backend.promotion.dto.ParsedPromotionReport;
import com.intermanage.backend.promotion.dto.PromotionImportOverview;
import com.intermanage.backend.promotion.dto.PromotionImportResponse;
import com.intermanage.backend.promotion.dto.PromotionReportRow;
import com.intermanage.backend.promotion.entity.PromotionImport;
import com.intermanage.backend.promotion.mapper.PromotionMapper;
import com.intermanage.backend.promotion.repository.PromotionImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Import raportu bieżących i przyszłych promocji (Taryfy.pdf). Promocje są scalane po sklepie, ITM8 i okresie: nowe
 * dopisywane, znane aktualizowane, a te, których zabrakło w nowszym raporcie, zostają w bazie.
 */
@Service
public class PromotionImportService {

    private static final Logger log = LoggerFactory.getLogger(PromotionImportService.class);

    private static final int HISTORY_SIZE = 30;
    private static final String IMPORT_LOCK_PREFIX = "promotion-import:";
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final PromotionReportParser parser;
    private final PromotionImportRepository imports;
    private final PromotionMapper mapper;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public PromotionImportService(TransactionTemplate transaction, PromotionReportParser parser,
            PromotionImportRepository imports, PromotionMapper mapper, StoreRepository stores,
            AppUserRepository users) {
        this.transaction = transaction;
        this.parser = parser;
        this.imports = imports;
        this.mapper = mapper;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PromotionImportOverview imports(UUID storeId) {
        return new PromotionImportOverview(imports.countActive(storeId, LocalDate.now(STORE_ZONE)),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(mapper::toResponse).toList());
    }

    public PromotionImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "promocje.pdf";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.badRequest("Listę promocji wgraj jako PDF wydrukowany z systemu sklepu.");
        }
        ParsedPromotionReport report = parse(file);
        PromotionImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import promocji do sklepu {} z {}: {} promocji, nowe {}, zmienione {}, brak w pliku {}", storeId,
                fileName, result.totalRows(), result.createdCount(), result.updatedCount(), result.missingCount());
        return result;
    }

    private ParsedPromotionReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("promotion-import-", ".pdf");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu promocji", e);
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

    private PromotionImportResponse merge(UUID storeId, ParsedPromotionReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        imports.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Ta lista promocji (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starsza niż ostatnio wgrana (" + REPORT_DATE_FORMAT.format(newest)
                    + "). Wgraj najnowszą.");
        }

        List<PromotionReportRow> rows = report.rows();
        PromotionImport saved = imports.saveAndFlush(new PromotionImport(store, fileName, report.generatedAt(),
                report.endFrom(), report.endTo(), userId != null ? users.getReferenceById(userId) : null,
                rows.size()));
        PromotionImportRepository.MergeCounts counts = imports.merge(storeId, saved.getId(),
                strings(rows, PromotionReportRow::itm8),
                strings(rows, PromotionReportRow::ean),
                strings(rows, PromotionReportRow::name),
                rows.stream().map(PromotionReportRow::startDate).toArray(LocalDate[]::new),
                rows.stream().map(PromotionReportRow::endDate).toArray(LocalDate[]::new),
                decimals(rows, PromotionReportRow::catalogPurchasePrice),
                decimals(rows, PromotionReportRow::regularSalePrice),
                decimals(rows, PromotionReportRow::promoPrice),
                decimals(rows, PromotionReportRow::marginPercent));
        long missing = imports.countMissing(storeId, saved.getId(), report.endFrom(), report.endTo());
        saved.recordResult((int) counts.getCreated(), (int) counts.getUpdated(), (int) missing);
        return mapper.toResponse(saved);
    }

    private static String[] strings(List<PromotionReportRow> rows, Function<PromotionReportRow, String> value) {
        return rows.stream().map(value).toArray(String[]::new);
    }

    private static BigDecimal[] decimals(List<PromotionReportRow> rows, Function<PromotionReportRow, BigDecimal> value) {
        return rows.stream().map(value).toArray(BigDecimal[]::new);
    }
}
