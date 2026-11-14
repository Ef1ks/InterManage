package com.intermanage.backend.cash.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.cash.component.CashPdf;
import com.intermanage.backend.cash.dto.CashClosingResponse;
import com.intermanage.backend.cash.dto.CashClosingSummary;
import com.intermanage.backend.cash.dto.CashReport;
import com.intermanage.backend.cash.entity.CashDocument;
import com.intermanage.backend.cash.entity.CashReportClosing;
import com.intermanage.backend.cash.entity.CashReportClosingRow;
import com.intermanage.backend.cash.entity.CashStamp;
import com.intermanage.backend.cash.mapper.CashMapper;
import com.intermanage.backend.cash.repository.CashDocumentRepository;
import com.intermanage.backend.cash.repository.CashReportClosingRepository;
import com.intermanage.backend.cash.repository.CashStampRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.service.DocumentService;

/**
 * Zakończone raporty dzienne. O 23:00 (czas sklepu) raport dnia zapisuje się sam jako zrzut ({@code RK 5/11/2026}):
 * stan poprzedni, obroty, stan obecny i dokumenty z kwotami z tej chwili. Dzień bez dokumentów nie ma raportu.
 * Dni pominięte (np. backend wyłączony o 23:00) zamykają się przy kolejnym przebiegu, od najstarszego.
 */
@Service
public class CashClosingService {

    /** Godzina zakończenia raportu dnia. */
    public static final LocalTime CLOSING_TIME = LocalTime.of(23, 0);

    private static final Logger log = LoggerFactory.getLogger(CashClosingService.class);

    private final CashReportClosingRepository closings;
    private final CashDocumentRepository documents;
    private final CashStampRepository stamps;
    private final CashService cash;
    private final CashMapper mapper;
    private final CashPdf pdf;
    private final TransactionTemplate transaction;

    public CashClosingService(CashReportClosingRepository closings, CashDocumentRepository documents,
            CashStampRepository stamps, CashService cash, CashMapper mapper, CashPdf pdf,
            PlatformTransactionManager transactionManager) {
        this.closings = closings;
        this.documents = documents;
        this.stamps = stamps;
        this.cash = cash;
        this.mapper = mapper;
        this.pdf = pdf;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Zamyka wszystkie zaległe dni do {@code now}: dziś po 23:00, wcześniej do wczoraj. Każdy dzień we własnej
     * transakcji, więc błąd jednego sklepu nie wstrzymuje pozostałych. Zwraca liczbę zamkniętych raportów.
     */
    public int closeDue(LocalDateTime now) {
        LocalDate lastDay = now.toLocalTime().isBefore(CLOSING_TIME) ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        List<Object[]> due = transaction.execute(status -> closings.findDaysToClose(lastDay));
        int closed = 0;
        for (Object[] day : due) {
            UUID storeId = (UUID) day[0];
            LocalDate date = (LocalDate) day[1];
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> close(storeId, date)))) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.error("Nie udało się zakończyć raportu kasowego sklepu {} z dnia {}", storeId, date, e);
            }
        }
        return closed;
    }

    private boolean close(UUID storeId, LocalDate day) {
        documents.lock(CashService.lockKey(storeId));
        if (closings.existsByStoreIdAndReportDate(storeId, day)) {
            return false;
        }
        CashReport report = cash.report(storeId, day, day);
        Map<UUID, CashDocument> byId = documents
                .findAllById(report.rows().stream().map(row -> row.document().id()).toList()).stream()
                .collect(Collectors.toMap(CashDocument::getId, Function.identity()));
        CashStamp stamp = stamps.findByStoreIdAndRemovedAtIsNull(storeId)
                .orElseGet(() -> report.rows().isEmpty() ? null
                        : byId.get(report.rows().getLast().document().id()).getStamp());
        CashReportClosing closing = new CashReportClosing(storeId, day, report.openingBalance(), report.receipts(),
                report.payments(), report.closingBalance(), stamp, null);
        for (CashReport.Row row : report.rows()) {
            closing.addRow(new CashReportClosingRow(byId.get(row.document().id()), row.balance()));
        }
        closings.save(closing);
        return true;
    }

    @Transactional(readOnly = true)
    public List<CashClosingSummary> list(UUID storeId) {
        return mapper.toSummaries(closings.findAllNewestFirst(storeId));
    }

    @Transactional(readOnly = true)
    public CashClosingResponse get(UUID storeId, UUID id) {
        CashReportClosing closing = find(storeId, id);
        return new CashClosingResponse(mapper.toSummary(closing), mapper.toRows(closing.getRows()));
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile pdf(UUID storeId, UUID id, UUID userId) {
        CashReportClosing closing = find(storeId, id);
        List<CashPdf.ReportRow> rows = new ArrayList<>();
        for (CashReportClosingRow row : closing.getRows()) {
            boolean receipt = row.getKind().isReceipt();
            rows.add(new CashPdf.ReportRow(row.getPosition(), closing.getReportDate(), row.getNumber(),
                    row.getContent(), row.getCounterparty(), receipt && !row.isCancelled() ? row.getAmount() : null,
                    !receipt && !row.isCancelled() ? row.getAmount() : null, row.isCancelled()));
        }
        byte[] content = pdf.report(new CashPdf.Report(closing.getNumber(), closing.getReportDate(),
                closing.getReportDate(), closing.getOpeningBalance(), closing.getReceipts(), closing.getPayments(),
                closing.getClosingBalance(), closing.getReceiptCount(), closing.getPaymentCount(), rows,
                closing.getStamp() == null ? null : closing.getStamp().getContent(), cash.storeLine(storeId),
                cash.userName(userId), LocalDateTime.now(StoreTime.ZONE)));
        String name = closing.getNumber().replace(' ', '-').replace('/', '-') + ".pdf";
        return new DocumentService.ExportFile(name, "application/pdf", content);
    }

    private CashReportClosing find(UUID storeId, UUID id) {
        return closings.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono raportu kasowego"));
    }
}
