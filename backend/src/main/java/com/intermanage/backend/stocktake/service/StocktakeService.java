package com.intermanage.backend.stocktake.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.document.repository.DocumentLineRepository;
import com.intermanage.backend.stocktake.component.StocktakeAnalyzer;
import com.intermanage.backend.stocktake.component.StocktakeReportParser;
import com.intermanage.backend.stocktake.dto.ParsedStocktakeReport;
import com.intermanage.backend.stocktake.dto.StocktakeBucket;
import com.intermanage.backend.stocktake.dto.StocktakeBucketSummary;
import com.intermanage.backend.stocktake.dto.StocktakeImportResponse;
import com.intermanage.backend.stocktake.dto.StocktakeReportRow;
import com.intermanage.backend.stocktake.dto.StocktakeResponse;
import com.intermanage.backend.stocktake.dto.StocktakeSummary;
import com.intermanage.backend.stocktake.entity.Stocktake;
import com.intermanage.backend.stocktake.entity.StocktakeImport;
import com.intermanage.backend.stocktake.mapper.StocktakeMapper;
import com.intermanage.backend.stocktake.repository.StocktakeImportRepository;
import com.intermanage.backend.stocktake.repository.StocktakeRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Weryfikacja inwentury przed zamknięciem. Raport porównania stanów wgrywa się po policzeniu całego sklepu albo działu,
 * a analiza liczy się przy każdym odczycie, bo każdy kolejny dzień sprzedaży i każde przeliczenie zmieniają jej wynik.
 */
@Service
public class StocktakeService {

    private static final Logger log = LoggerFactory.getLogger(StocktakeService.class);

    /** Sprzedaż po inwenturze: dłużej nic nie wnosi, a od zamknięcia inwentury mijają zwykle 2-3 dni. */
    private static final int SALES_WINDOW_DAYS = 14;
    /** Dostawa z dnia przed inwenturą mogła wjechać na sklep już po liczeniu. */
    private static final int DELIVERY_MARGIN_DAYS = 1;
    private static final String IMPORT_LOCK_PREFIX = "stocktake-import:";

    private final TransactionTemplate transaction;
    private final StocktakeReportParser parser;
    private final StocktakeAnalyzer analyzer;
    private final StocktakeRepository stocktakes;
    private final StocktakeImportRepository imports;
    private final DocumentLineRepository documentLines;
    private final StocktakeMapper mapper;
    private final StoreRepository stores;
    private final AppUserRepository users;

    public StocktakeService(TransactionTemplate transaction, StocktakeReportParser parser, StocktakeAnalyzer analyzer,
            StocktakeRepository stocktakes, StocktakeImportRepository imports, DocumentLineRepository documentLines,
            StocktakeMapper mapper, StoreRepository stores, AppUserRepository users) {
        this.transaction = transaction;
        this.parser = parser;
        this.analyzer = analyzer;
        this.stocktakes = stocktakes;
        this.imports = imports;
        this.documentLines = documentLines;
        this.mapper = mapper;
        this.stores = stores;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<StocktakeSummary> list(UUID storeId) {
        return stocktakes.findListed(storeId).stream()
                .map(row -> new StocktakeSummary(row.getId(), row.getNumber(), row.getName(), row.getStocktakeDate(),
                        row.getLineCount(), row.getNetValue(), row.getLastImportAt()))
                .toList();
    }

    public StocktakeImportResponse importReport(UUID storeId, MultipartFile file, UUID userId) {
        String fileName = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "inwentura.pdf";
        if (file.isEmpty()) {
            throw ApiException.badRequest("Wybierz plik do importu");
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw ApiException.badRequest("Porównanie stanów wgraj jako PDF wydrukowany z systemu sklepu.");
        }
        ParsedStocktakeReport report = parse(file);
        StocktakeImportResponse result = transaction.execute(status -> merge(storeId, report, fileName, userId));
        log.info("Import inwentury {} do sklepu {} z {}: {} artykułów, nowe {}, zmienione {}, bez różnicy {}",
                report.stocktakeNumber(), storeId, fileName, result.lineCount(), result.createdCount(),
                result.updatedCount(), result.resolvedCount());
        return result;
    }

    private ParsedStocktakeReport parse(MultipartFile file) {
        Path temp = null;
        try {
            temp = Files.createTempFile("stocktake-import-", ".pdf");
            file.transferTo(temp);
            return parser.parse(temp);
        } catch (InvalidReportException e) {
            throw ApiException.badRequest(e.getMessage())
                    .withProperty("rowErrors", e.errors())
                    .withProperty("rowErrorCount", e.errorCount());
        } catch (IOException e) {
            log.warn("Nie udało się odczytać pliku inwentury", e);
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

    private StocktakeImportResponse merge(UUID storeId, ParsedStocktakeReport report, String fileName, UUID userId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        if (report.storeNumber() != null && !report.storeNumber().equals(store.getNumber())) {
            throw ApiException.conflict("To raport sklepu " + report.storeNumber() + ", a wybrany jest sklep "
                    + store.getNumber() + " (" + store.getName() + "). Przełącz sklep albo wgraj właściwy plik.");
        }
        stocktakes.lockForImport(IMPORT_LOCK_PREFIX + storeId);

        Stocktake stocktake = stocktakes.findByStore_IdAndNumberAndStocktakeDate(storeId, report.stocktakeNumber(),
                report.stocktakeDate())
                .orElseGet(() -> stocktakes.saveAndFlush(new Stocktake(store, report.stocktakeNumber(),
                        report.stocktakeName(), report.stocktakeDate())));
        stocktake.restore();

        List<StocktakeReportRow> rows = report.rows();
        StocktakeImport saved = imports.saveAndFlush(new StocktakeImport(stocktake, fileName, report.selection(),
                report.wholeStore(), report.generatedAt(), report.pageCount(), rows.size(), report.differenceValue(),
                userId != null ? users.getReferenceById(userId) : null));
        StocktakeRepository.MergeCounts counts = stocktakes.merge(stocktake.getId(), saved.getId(), report.generatedAt(),
                strings(rows, StocktakeReportRow::itm8), strings(rows, StocktakeReportRow::ean),
                strings(rows, StocktakeReportRow::name), strings(rows, StocktakeReportRow::brand),
                strings(rows, StocktakeReportRow::blockType), decimals(rows, StocktakeReportRow::realQuantity),
                decimals(rows, StocktakeReportRow::theoreticalQuantity),
                decimals(rows, StocktakeReportRow::differenceQuantity),
                decimals(rows, StocktakeReportRow::differenceValue), strings(rows, StocktakeReportRow::articleKind));
        int resolved = report.wholeStore()
                ? stocktakes.resolveMissing(stocktake.getId(), saved.getId(), report.generatedAt()) : 0;
        saved.recordResult((int) counts.getCreated(), (int) counts.getUpdated(), resolved);
        return mapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public StocktakeResponse get(UUID storeId, UUID id) {
        Stocktake stocktake = stocktakes.findByIdAndStore_Id(id, storeId).filter(found -> !found.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono inwentury"));
        LocalDate from = stocktake.getStocktakeDate();
        LocalDate to = from.plusDays(SALES_WINDOW_DAYS);
        Map<String, BigDecimal> delivered = deliveredAfter(storeId, from, to);

        List<StocktakeAnalyzer.Article> articles = new ArrayList<>();
        int resolved = 0;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal absolute = BigDecimal.ZERO;
        BigDecimal firstAbsolute = BigDecimal.ZERO;
        for (StocktakeRepository.Line line : stocktakes.findLines(id, storeId, from, to)) {
            firstAbsolute = firstAbsolute.add(line.getFirstDifferenceValue().abs());
            if (line.getResolved()) {
                resolved++;
                continue;
            }
            net = net.add(line.getDifferenceValue());
            absolute = absolute.add(line.getDifferenceValue().abs());
            articles.add(new StocktakeAnalyzer.Article(line.getItm8(), line.getEan(), line.getName(), line.getBrand(),
                    line.getBlockType(), line.getRealQuantity(), line.getTheoreticalQuantity(),
                    line.getDifferenceQuantity(), line.getDifferenceValue(), line.getFirstDifferenceValue(),
                    line.getArticleKind(), line.getRegionCode(), line.getRegionName(), line.getSoldAfter(),
                    delivered.getOrDefault(line.getItm8(), BigDecimal.ZERO)));
        }
        StocktakeAnalyzer.Result result = analyzer.analyze(articles);
        List<StocktakeBucketSummary> buckets = new ArrayList<>();
        for (StocktakeBucket bucket : StocktakeBucket.values()) {
            buckets.add(new StocktakeBucketSummary(bucket, result.counts().get(bucket), result.values().get(bucket)));
        }
        return new StocktakeResponse(stocktake.getId(), stocktake.getNumber(), stocktake.getName(), from,
                stocktakes.findLastSaleDay(storeId, from, to), articles.size(), resolved, net, absolute, firstAbsolute,
                buckets, result.lines(), result.pairs(),
                imports.findByStocktake_IdOrderByImportedAtDesc(id).stream().map(mapper::toResponse).toList());
    }

    /** Przyjęcia PZ (też pod lokalnym ITM8 dostawcy, po EAN-ie) i towar z innych sklepów. */
    private Map<String, BigDecimal> deliveredAfter(UUID storeId, LocalDate from, LocalDate to) {
        var since = from.minusDays(DELIVERY_MARGIN_DAYS).atStartOfDay();
        var until = to.plusDays(1).atStartOfDay();
        Map<String, BigDecimal> delivered = new HashMap<>();
        documentLines.sumDeliveredBetween(storeId, since, until)
                .forEach(row -> delivered.merge(row.getItm8(), row.getQuantity(), BigDecimal::add));
        stocktakes.sumTransfersIn(storeId, since, until)
                .forEach(row -> delivered.merge(row.getItm8(), row.getQuantity(), BigDecimal::add));
        return delivered;
    }

    @Transactional
    public void delete(UUID storeId, UUID id, UUID userId) {
        Stocktake stocktake = stocktakes.findByIdAndStore_Id(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono inwentury"));
        stocktake.delete(userId != null ? users.getReferenceById(userId) : null);
    }

    @Transactional
    public void restore(UUID storeId, UUID id) {
        stocktakes.findByIdAndStore_Id(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono inwentury"))
                .restore();
    }

    private static String[] strings(List<StocktakeReportRow> rows, Function<StocktakeReportRow, String> value) {
        return rows.stream().map(value).toArray(String[]::new);
    }

    private static BigDecimal[] decimals(List<StocktakeReportRow> rows, Function<StocktakeReportRow, BigDecimal> value) {
        return rows.stream().map(value).toArray(BigDecimal[]::new);
    }
}
