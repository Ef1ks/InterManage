package com.intermanage.backend.nomenclature.service;

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
import com.intermanage.backend.nomenclature.component.NomenclatureReportParser;
import com.intermanage.backend.nomenclature.dto.NomenclatureImportOverview;
import com.intermanage.backend.nomenclature.dto.NomenclatureImportResponse;
import com.intermanage.backend.nomenclature.dto.NomenclatureReportRow;
import com.intermanage.backend.nomenclature.dto.ParsedNomenclatureReport;
import com.intermanage.backend.nomenclature.entity.Nomenclature;
import com.intermanage.backend.nomenclature.entity.NomenclatureImport;
import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;
import com.intermanage.backend.nomenclature.mapper.NomenclatureMapper;
import com.intermanage.backend.nomenclature.repository.NomenclatureImportRepository;
import com.intermanage.backend.nomenclature.repository.NomenclatureRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Nomenklatura sklepu z raportu "Lista nomenklatur" (PDF). Scalanie po kodzie: nowe pozycje są dodawane, znane
 * aktualizowane, a te, których zabrakło w pliku, zostają ({@code last_import_id} wskazuje ostatni raport z nimi).
 * Z nomenklatury raporty sprzedaży biorą nazwy rejonów, rodzin i podrodzin.
 */
@Service
public class NomenclatureService {

    private static final Logger log = LoggerFactory.getLogger(NomenclatureService.class);

    private static final int HISTORY_SIZE = 30;
    private static final String IMPORT_LOCK_PREFIX = "nomenclature-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final NomenclatureReportParser reportParser;
    private final NomenclatureMapper nomenclatureMapper;
    private final NomenclatureImportRepository imports;
    private final NomenclatureRepository nomenclatures;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public NomenclatureService(TransactionTemplate transaction, NomenclatureReportParser reportParser,
            NomenclatureMapper nomenclatureMapper, NomenclatureImportRepository imports,
            NomenclatureRepository nomenclatures, StoreRepository stores, AppUserRepository users) {
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.nomenclatureMapper = nomenclatureMapper;
        this.imports = imports;
        this.nomenclatures = nomenclatures;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public NomenclatureImportOverview imports(UUID storeId) {
        return new NomenclatureImportOverview(
                nomenclatures.countByStoreIdAndLevel(storeId, NomenclatureLevel.REGION),
                nomenclatures.countByStoreIdAndLevel(storeId, NomenclatureLevel.FAMILY),
                nomenclatures.countByStoreIdAndLevel(storeId, NomenclatureLevel.SUBFAMILY),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(nomenclatureMapper::toResponse).toList());
    }

    public NomenclatureImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "nomenklatury.pdf";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.badRequest("Listę nomenklatur wgrywa się jako PDF z systemu sklepu.");
        }

        ParsedNomenclatureReport report = parse(file);
        NomenclatureImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import nomenklatury do sklepu {} z {}: {} pozycji, nowe {}, zmienione {}, brak w pliku {}", storeId,
                fileName, result.totalRows(), result.createdCount(), result.updatedCount(), result.missingCount());
        return result;
    }

    private ParsedNomenclatureReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("nomenclature-import-", ".pdf");
            file.transferTo(temp);
            return reportParser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu nomenklatury", e);
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

    private NomenclatureImportResponse merge(UUID storeId, ParsedNomenclatureReport report, String fileName,
            UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        nomenclatures.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Ta lista nomenklatur (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starsza niż ostatnio wgrana (" + REPORT_DATE_FORMAT.format(newest) + "). Wgraj najnowszą.");
        }

        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        NomenclatureImport nomenclatureImport = imports.save(
                new NomenclatureImport(store, fileName, report.generatedAt(), user, report.rows().size()));

        Map<String, Nomenclature> known = new HashMap<>();
        nomenclatures.findAllByStore(storeId).forEach(item -> known.put(item.getCode(), item));
        // Pozycje z pliku po kodzie, także nowe: przez nie dzieci znajdują świeżo dodanego rodzica.
        Map<String, Nomenclature> inFile = new HashMap<>();
        int created = 0;
        int updated = 0;
        // Wiersze są w kolejności kodów, więc rejon jest zawsze przed swoimi rodzinami, a rodzina przed podrodzinami.
        for (NomenclatureReportRow row : report.rows()) {
            Nomenclature item = known.remove(row.code());
            boolean isNew = item == null;
            if (isNew) {
                Nomenclature parent = row.code().length() == 2 ? null
                        : inFile.get(row.code().substring(0, row.code().length() - 2));
                item = new Nomenclature(store, row.code(), parent, nomenclatureImport);
            }
            boolean changed = item.update(row.name(), row.vatCode(), row.unit(), row.labelCount(), row.labelFormat(),
                    row.tillMode(), row.saleType(), row.stockMode());
            if (isNew) {
                nomenclatures.save(item);
                created++;
            } else {
                item.markSeen(nomenclatureImport);
                if (changed) {
                    updated++;
                }
            }
            inFile.put(row.code(), item);
        }
        // W known zostały pozycje, których nie było w pliku - nie usuwamy ich.
        nomenclatureImport.recordResult(created, updated, known.size());
        return nomenclatureMapper.toResponse(nomenclatureImport);
    }
}
