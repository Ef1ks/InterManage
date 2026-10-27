package com.intermanage.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.component.SalesReportParser;
import com.intermanage.backend.dto.SalesImportOverview;
import com.intermanage.backend.dto.SalesImportResponse;
import com.intermanage.backend.dto.SalesReportBatch;
import com.intermanage.backend.dto.SalesReportHeader;
import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.entity.SalesImport;
import com.intermanage.backend.entity.Store;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.exception.InvalidReportException;
import com.intermanage.backend.mapper.SalesMapper;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.repository.SaleRepository;
import com.intermanage.backend.repository.SalesImportRepository;
import com.intermanage.backend.repository.StoreRepository;

/**
 * Import raportu sprzedaży wg artykułu i dnia. Pliki mają do ok. 500 tys. wierszy (na start ok. 2 mln w kilku
 * plikach, potem 15-20 tys. dziennie), więc nic nie przechodzi przez encje: parser czyta plik strumieniowo
 * i oddaje paczki po {@value #BATCH_SIZE} wierszy, a każda paczka to jedno zapytanie w bazie. Całość idzie w jednej
 * transakcji: błędny wiersz gdziekolwiek w pliku wycofuje wszystko, co już zapisano.
 * <p>
 * Raz zapisana sprzedaż jest jedynym źródłem prawdy: import tylko dopisuje dni artykułów, których jeszcze nie ma,
 * a już zapisane pomija, nawet gdy plik ma inne wartości (liczy je wtedy jako niezgodne). Dlatego kolejność i wiek
 * raportów nie mają znaczenia - starszy plik może co najwyżej dopisać brakujące dni.
 * <p>
 * Dzień, w którym wygenerowano raport, nie jest zapisywany: sklep jeszcze sprzedawał, więc jego sprzedaż jest niepełna.
 * Takie wiersze liczymy jako niezakończone ({@code incomplete_count}); dzień dopisze raport z kolejnego dnia.
 */
@Service
public class SalesImportService {

    private static final Logger log = LoggerFactory.getLogger(SalesImportService.class);

    /** Kilka tysięcy wierszy na zapytanie: mniej zapytań, a tablice parametrów wciąż są małe. */
    static final int BATCH_SIZE = 5_000;
    private static final int HISTORY_SIZE = 30;
    private static final String IMPORT_LOCK_PREFIX = "sales-import:";
    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");

    private final TransactionTemplate transaction;
    private final SalesReportParser reportParser;
    private final SalesMapper salesMapper;
    private final SalesImportRepository imports;
    private final SaleRepository sales;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public SalesImportService(TransactionTemplate transaction, SalesReportParser reportParser, SalesMapper salesMapper,
            SalesImportRepository imports, SaleRepository sales, StoreRepository stores, AppUserRepository users) {
        this.transaction = transaction;
        this.reportParser = reportParser;
        this.salesMapper = salesMapper;
        this.imports = imports;
        this.sales = sales;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public SalesImportOverview imports(UUID storeId) {
        SaleRepository.Coverage coverage = sales.findCoverage(storeId);
        return new SalesImportOverview(imports.countRows(storeId), coverage.getFirstDate(), coverage.getLastDate(),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(salesMapper::toResponse).toList());
    }

    public SalesImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "sprzedaz.xlsx";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }

        long started = System.nanoTime();
        Path temp = null;
        try {
            temp = Files.createTempFile("sales-import-", ".xlsx");
            file.transferTo(temp);
            Path report = temp;
            SalesImportResponse result = transaction.execute(status -> importFile(storeId, report, fileName, userId));
            log.info("Import sprzedaży do sklepu {} z {} ({} - {}): {} wierszy, dopisane {}, już były {} (niezgodne {}), {} ms",
                    storeId, fileName, result.periodFrom(), result.periodTo(), result.totalRows(), result.createdCount(),
                    result.existingCount(), result.conflictCount(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu sprzedaży", e);
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

    private SalesImportResponse importFile(UUID storeId, Path file, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        imports.lockForImport(IMPORT_LOCK_PREFIX + storeId);
        AppUser user = userId != null ? users.getReferenceById(userId) : null;

        var sink = new SalesReportParser.Sink() {
            SalesImport salesImport;
            LocalDate firstIncompleteDay;
            long created;
            long conflicting;
            long incomplete;

            @Override
            public void header(SalesReportHeader header) {
                checkHeader(store, header);
                firstIncompleteDay = firstIncompleteDay(header);
                salesImport = imports.saveAndFlush(new SalesImport(store, fileName, header.generatedAt(),
                        header.periodFrom(), header.periodTo(), user));
            }

            @Override
            public void batch(SalesReportBatch batch) {
                SalesReportBatch complete = batch.before(firstIncompleteDay);
                incomplete += batch.size() - complete.size();
                if (complete.size() == 0) {
                    return;
                }
                SaleRepository.InsertCounts counts = sales.insertNew(storeId, salesImport.getId(), complete.dates(),
                        complete.itm8s(), complete.eans(), complete.quantities(), complete.costValues(),
                        complete.saleValuesGross(), complete.vatValues(), complete.marginValues(),
                        complete.marginPercents());
                created += counts.getCreated();
                conflicting += counts.getConflicting();
            }
        };

        SalesReportParser.Summary summary;
        try {
            summary = reportParser.parse(file, BATCH_SIZE, sink);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku importu sprzedaży", e);
            throw ApiException.badRequest("Nie udało się odczytać pliku. Sprawdź, czy nie jest uszkodzony.");
        }
        sink.salesImport.recordResult(summary.totalRows(), Math.toIntExact(sink.created),
                Math.toIntExact(sink.conflicting), Math.toIntExact(sink.incomplete));
        return salesMapper.toResponse(sink.salesImport);
    }

    /**
     * Pierwszy dzień, którego sprzedaż w raporcie jest niepełna: dzień wygenerowania raportu (sklep jeszcze wtedy
     * sprzedawał), a bez daty w nagłówku - dzisiejszy. Takich dni nie zapisujemy, bo zapisanej sprzedaży nie da się
     * potem poprawić; dopisze je raport wygenerowany następnego dnia.
     */
    static LocalDate firstIncompleteDay(SalesReportHeader header) {
        LocalDate today = LocalDate.now(STORE_ZONE);
        LocalDate generated = header.generatedAt() == null ? today : header.generatedAt().toLocalDate();
        return generated.isBefore(today) ? generated : today;
    }

    private void checkHeader(Store store, SalesReportHeader header) {
        if (header.storeNumber() != null && !header.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + header.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
    }
}
