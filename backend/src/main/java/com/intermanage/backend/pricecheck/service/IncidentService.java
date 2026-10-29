package com.intermanage.backend.pricecheck.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
import com.intermanage.backend.pricecheck.component.IncidentReportParser;
import com.intermanage.backend.pricecheck.dto.IncidentImportResponse;
import com.intermanage.backend.pricecheck.dto.IncidentOverview;
import com.intermanage.backend.pricecheck.dto.ParsedIncidentReport;
import com.intermanage.backend.pricecheck.dto.ParsedIncidentReport.Item;
import com.intermanage.backend.pricecheck.entity.IncidentImport;
import com.intermanage.backend.pricecheck.mapper.PriceCheckMapper;
import com.intermanage.backend.pricecheck.repository.IncidentImportRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Incydenty zamówień do bazy: artykuły, których baza nie dowiozła, z przyczyną. Dzień incydentu pochodzi z nazwy
 * pliku ({@code <nr sklepu>_RRMMDD_...}). Ponowne wgranie pliku z tego samego dnia nadpisuje jego artykuły
 * i dopisuje nowe, niczego nie usuwa.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private static final int HISTORY_SIZE = 10;
    private static final String IMPORT_LOCK_PREFIX = "incident-import:";
    /** "10023_261028_261028_W_NS_INCYDENTY.xlsx": numer sklepu i dzień RRMMDD. */
    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{3,})_(\\d{2})(\\d{2})(\\d{2})(?:_|\\.|$)");

    private final TransactionTemplate transaction;
    private final IncidentReportParser parser;
    private final IncidentImportRepository imports;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final PriceCheckMapper mapper;

    public IncidentService(TransactionTemplate transaction, IncidentReportParser parser,
            IncidentImportRepository imports, StoreRepository stores, AppUserRepository users,
            PriceCheckMapper mapper) {
        this.transaction = transaction;
        this.parser = parser;
        this.imports = imports;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public IncidentOverview overview(UUID storeId) {
        return new IncidentOverview(imports.findLastIncidentDate(storeId).orElse(null),
                imports.findLatest(storeId, Limit.of(HISTORY_SIZE)).stream().map(mapper::toResponse).toList());
    }

    public IncidentImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "";
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xlsm")) {
            throw ApiException.badRequest("Obsługujemy pliki .xlsx i .xlsm. Plik .xls zapisz w Excelu jako .xlsx.");
        }
        Matcher name = FILE_NAME.matcher(fileName);
        LocalDate incidentDate = name.find() ? date(name) : null;
        if (incidentDate == null) {
            throw ApiException.badRequest("Nazwa pliku musi zaczynać się od numeru sklepu i daty incydentu "
                    + "RRMMDD, np. 10023_261028_261028_W_NS_INCYDENTY.xlsx. Nie zmieniaj nazwy pliku z systemu.");
        }
        String fileStore = name.group(1);
        ParsedIncidentReport report = parse(file);
        IncidentImportResponse result = transaction.execute(
                status -> merge(storeId, fileStore, incidentDate, report, fileName, userId));
        log.info("Import incydentów z {} do sklepu {} z {}: {} artykułów, nowe {}", incidentDate, storeId, fileName,
                result.totalRows(), result.addedCount());
        return result;
    }

    private static LocalDate date(Matcher name) {
        try {
            return LocalDate.of(2000 + Integer.parseInt(name.group(2)), Integer.parseInt(name.group(3)),
                    Integer.parseInt(name.group(4)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private ParsedIncidentReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("incident-import-", ".xlsx");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku incydentów", e);
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

    private IncidentImportResponse merge(UUID storeId, String fileStore, LocalDate incidentDate,
            ParsedIncidentReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        for (String number : Stream.concat(Stream.of(fileStore), report.storeNumbers().stream()).toList()) {
            if (!number.equals(store.getNumber())) {
                throw ApiException.conflict("To incydenty sklepu " + number + ", a wybrany jest sklep "
                        + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
            }
        }
        imports.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        List<Item> items = report.items();
        IncidentImport saved = imports.saveAndFlush(new IncidentImport(store, fileName, incidentDate,
                userId != null ? users.getReferenceById(userId) : null, items.size()));
        long added = imports.merge(storeId, saved.getId(), incidentDate, column(items, Item::itm8),
                column(items, Item::ean), column(items, Item::name), column(items, Item::brand),
                column(items, Item::reason), column(items, Item::comment), column(items, Item::incidentCode),
                column(items, item -> plain(item.orderedQuantity())),
                column(items, item -> plain(item.deliveredQuantity())));
        saved.recordResult((int) added);
        return mapper.toResponse(saved);
    }

    private static String[] column(List<Item> items, Function<Item, String> value) {
        return items.stream().map(value).toArray(String[]::new);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
