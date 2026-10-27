package com.intermanage.backend.supplier.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.supplier.component.SupplierReportParser;
import com.intermanage.backend.supplier.dto.CreateSupplierRequest;
import com.intermanage.backend.supplier.dto.ParsedSupplierReport;
import com.intermanage.backend.supplier.dto.SupplierImportOverview;
import com.intermanage.backend.supplier.dto.SupplierImportResponse;
import com.intermanage.backend.supplier.dto.SupplierReportRow;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.entity.Supplier;
import com.intermanage.backend.supplier.entity.SupplierImport;
import com.intermanage.backend.supplier.mapper.SupplierMapper;
import com.intermanage.backend.supplier.repository.SupplierImportRepository;
import com.intermanage.backend.supplier.repository.SupplierRepository;

/**
 * Dostawcy sklepu. Raport "Lista dostawców" jest scalany po kodzie dostawcy: nowi są dodawani, znani aktualizowani,
 * a ci, których zabrakło w pliku, zostają. Dostawców spoza systemu sklepu dodaje się ręcznie (bez kodu).
 */
@Service
public class SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SupplierService.class);

    private static final int HISTORY_SIZE = 30;
    private static final String IMPORT_LOCK_PREFIX = "supplier-import:";
    private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TransactionTemplate transaction;
    private final SupplierReportParser reportParser;
    private final SupplierMapper supplierMapper;
    private final SupplierImportRepository imports;
    private final SupplierRepository suppliers;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public SupplierService(TransactionTemplate transaction, SupplierReportParser reportParser,
            SupplierMapper supplierMapper, SupplierImportRepository imports, SupplierRepository suppliers,
            StoreRepository stores, AppUserRepository users) {
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.supplierMapper = supplierMapper;
        this.imports = imports;
        this.suppliers = suppliers;
        this.stores = stores;
        this.users = users;
    }

    /** Wszyscy dostawcy sklepu po nazwie; jest ich kilkuset, więc frontend filtruje ich sam. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> list(UUID storeId) {
        return suppliers.findByStore(storeId).stream().map(supplierMapper::toResponse).toList();
    }

    @Transactional
    public SupplierResponse create(UUID storeId, CreateSupplierRequest request, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String name = request.name().trim().replaceAll("\\s+", " ");
        if (suppliers.existsManualByName(storeId, name)) {
            throw ApiException.conflict("Dostawca " + name + " jest już na liście");
        }
        String nip = request.nip() == null ? "" : request.nip().replaceAll("[\\s-]", "");
        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        Supplier supplier = suppliers.save(Supplier.manual(store, name, nip.isEmpty() ? null : nip, user));
        return supplierMapper.toResponse(supplier);
    }

    @Transactional(readOnly = true)
    public SupplierImportOverview imports(UUID storeId) {
        return new SupplierImportOverview(suppliers.countByStoreId(storeId), suppliers.countManual(storeId),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(supplierMapper::toResponse).toList());
    }

    public SupplierImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "dostawcy.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }

        ParsedSupplierReport report = parse(file);
        SupplierImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import dostawców do sklepu {} z {}: {} wierszy, nowi {}, zmienieni {}, brak w pliku {}", storeId,
                fileName, result.totalRows(), result.createdCount(), result.updatedCount(), result.missingCount());
        return result;
    }

    private ParsedSupplierReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("supplier-import-", ".xlsx");
            file.transferTo(temp);
            return reportParser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu dostawców", e);
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

    private SupplierImportResponse merge(UUID storeId, ParsedSupplierReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        suppliers.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        LocalDateTime newest = imports.findNewestReportGeneratedAt(storeId).orElse(null);
        if (report.generatedAt() != null && newest != null && report.generatedAt().isBefore(newest)) {
            throw ApiException.conflict("Ta lista dostawców (" + REPORT_DATE_FORMAT.format(report.generatedAt())
                    + ") jest starsza niż ostatnio wgrana (" + REPORT_DATE_FORMAT.format(newest)
                    + "). Wgraj najnowszą.");
        }

        AppUser user = userId != null ? users.getReferenceById(userId) : null;
        SupplierImport supplierImport = imports.save(
                new SupplierImport(store, fileName, report.generatedAt(), user, report.rows().size()));

        Map<String, Supplier> known = new HashMap<>();
        suppliers.findImported(storeId).forEach(supplier -> known.put(supplier.getCode(), supplier));
        int created = 0;
        int updated = 0;
        for (SupplierReportRow row : report.rows()) {
            Supplier supplier = known.remove(row.code());
            if (supplier == null) {
                suppliers.save(Supplier.imported(store, row, supplierImport));
                created++;
            } else if (supplier.apply(row, supplierImport)) {
                updated++;
            }
        }
        // W known zostali ci, których nie było w pliku - nie usuwamy ich, last_import_id wskazuje ostatni raport z nimi.
        supplierImport.recordResult(created, updated, known.size());
        return supplierMapper.toResponse(supplierImport);
    }
}
