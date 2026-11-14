package com.intermanage.backend.cash.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.cash.component.CashPdf;
import com.intermanage.backend.cash.dto.CashDocumentRequest;
import com.intermanage.backend.cash.dto.CashDocumentResponse;
import com.intermanage.backend.cash.dto.CashOpeningBalanceRequest;
import com.intermanage.backend.cash.dto.CashReport;
import com.intermanage.backend.cash.dto.CashSettings;
import com.intermanage.backend.cash.entity.CashDocument;
import com.intermanage.backend.cash.entity.CashDocumentKind;
import com.intermanage.backend.cash.entity.CashOpeningBalance;
import com.intermanage.backend.cash.entity.CashReportClosingRow;
import com.intermanage.backend.cash.entity.CashStamp;
import com.intermanage.backend.cash.mapper.CashMapper;
import com.intermanage.backend.cash.repository.CashDocumentRepository;
import com.intermanage.backend.cash.repository.CashOpeningBalanceRepository;
import com.intermanage.backend.cash.repository.CashReportClosingRepository;
import com.intermanage.backend.cash.repository.CashStampRepository;
import com.intermanage.backend.common.component.ReportPeriod;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Raport kasowy sklepu: dokumenty KP i KW, ich wydruki z pieczątką, raport bieżący za okres, pieczątka i stan
 * początkowy. Jedna kasa na sklep. Dokumentów się nie zmienia: pomyłkę anuluje się, numer zostaje w historii. Stan
 * kasy nie może spaść poniżej zera w żadnym miejscu historii. Dni z zakończonym raportem (o 23:00, {@link
 * CashClosingService}) są zamknięte: nie ma w nich nowych dokumentów ani anulowania.
 */
@Service
public class CashService {

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CashDocumentRepository documents;
    private final CashStampRepository stamps;
    private final CashOpeningBalanceRepository openings;
    private final CashReportClosingRepository closings;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final CashMapper mapper;
    private final CashPdf pdf;

    public CashService(CashDocumentRepository documents, CashStampRepository stamps,
            CashOpeningBalanceRepository openings, CashReportClosingRepository closings, StoreRepository stores,
            AppUserRepository users, CashMapper mapper, CashPdf pdf) {
        this.documents = documents;
        this.stamps = stamps;
        this.openings = openings;
        this.closings = closings;
        this.stores = stores;
        this.users = users;
        this.mapper = mapper;
        this.pdf = pdf;
    }

    // ---------------------------------------------------------------- raport bieżący

    @Transactional(readOnly = true)
    public CashReport report(UUID storeId, LocalDate from, LocalDate to) {
        ReportPeriod.check(from, to);
        Ledger ledger = ledger(storeId);
        BigDecimal opening = ledger.balanceBefore(from);
        BigDecimal balance = opening;
        BigDecimal receipts = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        List<CashReport.Row> rows = new ArrayList<>();
        for (CashDocument document : ledger.documents()) {
            if (document.getIssueDate().isBefore(from) || document.getIssueDate().isAfter(to)) {
                continue;
            }
            if (document.isCancelled()) {
                rows.add(new CashReport.Row(mapper.toResponse(document), null));
                continue;
            }
            balance = balance.add(document.signedAmount());
            if (document.getKind().isReceipt()) {
                receipts = receipts.add(document.getAmount());
            } else {
                payments = payments.add(document.getAmount());
            }
            rows.add(new CashReport.Row(mapper.toResponse(document), balance));
        }
        return new CashReport(from, to, opening, receipts, payments, balance, rows, settings(storeId));
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile reportPdf(UUID storeId, LocalDate from, LocalDate to, UUID userId) {
        CashReport report = report(storeId, from, to);
        List<CashPdf.ReportRow> rows = new ArrayList<>();
        int position = 1;
        int receiptCount = 0;
        int paymentCount = 0;
        for (CashReport.Row row : report.rows()) {
            CashDocumentResponse document = row.document();
            boolean receipt = document.kind().isReceipt();
            boolean cancelled = document.cancelledAt() != null;
            if (!cancelled) {
                if (receipt) {
                    receiptCount++;
                } else {
                    paymentCount++;
                }
            }
            rows.add(new CashPdf.ReportRow(position++, document.issueDate(), document.number(), document.content(),
                    document.counterparty(), receipt && !cancelled ? document.amount() : null,
                    !receipt && !cancelled ? document.amount() : null, cancelled));
        }
        byte[] stamp = stamps.findByStoreIdAndRemovedAtIsNull(storeId).map(CashStamp::getContent).orElse(null);
        byte[] content = pdf.report(new CashPdf.Report(null, from, to, report.openingBalance(), report.receipts(),
                report.payments(), report.closingBalance(), receiptCount, paymentCount, rows, stamp,
                storeLine(storeId), userName(userId), LocalDateTime.now(StoreTime.ZONE)));
        String name = "raport-kasowy-" + FILE_DATE.format(from)
                + (from.equals(to) ? "" : "-" + FILE_DATE.format(to)) + ".pdf";
        return new DocumentService.ExportFile(name, "application/pdf", content);
    }

    // ---------------------------------------------------------------- dokumenty

    /** Historia: wszystkie dokumenty sklepu od najnowszego, także anulowane. */
    @Transactional(readOnly = true)
    public List<CashDocumentResponse> history(UUID storeId) {
        List<CashDocument> all = new ArrayList<>(documents.findAllInOrder(storeId));
        all.sort(Comparator.comparing(CashDocument::getIssueDate).thenComparing(CashDocument::getCreatedAt).reversed());
        return all.stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public CashDocumentResponse create(UUID storeId, CashDocumentRequest request, UUID userId) {
        documents.lock(lockKey(storeId));
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        if (request.issueDate().isAfter(today)) {
            throw ApiException.badRequest("Dokument nie może mieć daty z przyszłości");
        }
        requireOpenDay(storeId, request.issueDate(), "wystawić w nim dokumentu");
        CashStamp stamp = stamps.findByStoreIdAndRemovedAtIsNull(storeId).orElseThrow(() -> ApiException.conflict(
                "Najpierw wgraj skan pieczątki sklepu: każdy dokument kasowy drukuje się z pieczątką"));
        openings.findByStoreIdAndRemovedAtIsNull(storeId).ifPresent(opening -> {
            if (request.issueDate().isBefore(opening.getBalanceDate())) {
                throw ApiException.badRequest("Raport kasowy jest prowadzony od "
                        + opening.getBalanceDate().format(DAY) + ", dokument nie może mieć wcześniejszej daty");
            }
        });
        LocalDate date = request.issueDate();
        int number = documents.lastNumber(storeId, request.kind(), date.getYear(), date.getMonthValue()) + 1;
        CashDocument document = documents.save(new CashDocument(storeId, request.kind(), number, date,
                request.amount(), request.operation().strip(), blankToNull(request.title()),
                blankToNull(request.counterparty()), blankToNull(request.counterpartyAddress()), stamp,
                users.getReferenceById(userId)));
        documents.flush();
        ledger(storeId).requireNonNegative();
        return mapper.toResponse(document);
    }

    @Transactional
    public CashDocumentResponse cancel(UUID storeId, UUID id, String reason, UUID userId) {
        documents.lock(lockKey(storeId));
        CashDocument document = find(storeId, id);
        if (document.isCancelled()) {
            throw ApiException.conflict("Dokument " + document.getNumber() + " jest już anulowany");
        }
        requireOpenDay(storeId, document.getIssueDate(), "anulować jego dokumentów");
        document.cancel(reason.strip(), users.getReferenceById(userId));
        documents.flush();
        ledger(storeId).requireNonNegative();
        return mapper.toResponse(document);
    }

    /** Cofnięcie anulowania („Przywróć”). */
    @Transactional
    public CashDocumentResponse restore(UUID storeId, UUID id) {
        documents.lock(lockKey(storeId));
        CashDocument document = find(storeId, id);
        if (document.isCancelled()) {
            requireOpenDay(storeId, document.getIssueDate(), "przywrócić jego dokumentów");
            document.restore();
            documents.flush();
            ledger(storeId).requireNonNegative();
        }
        return mapper.toResponse(document);
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile documentPdf(UUID storeId, UUID id) {
        CashDocument document = find(storeId, id);
        String issuedBy = document.getCreatedBy() == null ? null : document.getCreatedBy().getDisplayName();
        CashReportClosingRow inReport = closings.findRowOfDocument(document.getId()).orElse(null);
        byte[] content = pdf.voucher(new CashPdf.Voucher(document.getKind(), document.getNumber(),
                document.getIssueDate(), document.getAmount(), document.getOperation(), document.getTitle(),
                document.getCounterparty(), document.getCounterpartyAddress(), issuedBy,
                document.getStamp().getContent(), storeLine(storeId), document.isCancelled(),
                document.getCancelReason(), inReport == null ? null : inReport.getClosing().getNumber(),
                inReport == null ? null : inReport.getPosition()));
        String name = document.getNumber().replace(' ', '-').replace('/', '-') + ".pdf";
        return new DocumentService.ExportFile(name, "application/pdf", content);
    }

    // ---------------------------------------------------------------- ustawienia

    @Transactional(readOnly = true)
    public CashSettings settings(UUID storeId) {
        return new CashSettings(
                stamps.findByStoreIdAndRemovedAtIsNull(storeId).map(mapper::toStamp).orElse(null),
                openings.findByStoreIdAndRemovedAtIsNull(storeId).map(mapper::toOpeningBalance).orElse(null),
                closings.lastClosedDate(storeId).orElse(null),
                usedOperations(storeId));
    }

    private Map<CashDocumentKind, List<String>> usedOperations(UUID storeId) {
        Map<CashDocumentKind, List<String>> operations = new EnumMap<>(CashDocumentKind.class);
        for (CashDocumentKind kind : CashDocumentKind.values()) operations.put(kind, new ArrayList<>());
        for (Object[] row : documents.usedOperations(storeId)) {
            operations.get((CashDocumentKind) row[0]).add((String) row[1]);
        }
        return operations;
    }

    /** Nowa pieczątka zastępuje poprzednią (znacznik); wystawione dokumenty drukują się dalej ze swoją. */
    @Transactional
    public CashSettings uploadStamp(UUID storeId, MultipartFile file, UUID userId) {
        byte[] content = ScanFiles.content(file);
        String contentType = ScanFiles.contentType(content);
        if (!"image/png".equals(contentType) && !"image/jpeg".equals(contentType)) {
            throw ApiException.badRequest("Wgraj skan pieczątki jako PNG albo JPG");
        }
        AppUser user = users.getReferenceById(userId);
        stamps.findByStoreIdAndRemovedAtIsNull(storeId).ifPresent(previous -> {
            previous.remove(user);
            stamps.flush();
        });
        stamps.save(new CashStamp(storeId, ScanFiles.fileName(file, contentType), contentType, content, user));
        return settings(storeId);
    }

    @Transactional
    public CashSettings removeStamp(UUID storeId, UUID userId) {
        stamps.findByStoreIdAndRemovedAtIsNull(storeId).ifPresent(stamp -> stamp.remove(users.getReferenceById(userId)));
        return settings(storeId);
    }

    /** „Przywróć” po usunięciu albo wymianie: wskazana pieczątka znów obowiązuje. */
    @Transactional
    public CashSettings restoreStamp(UUID storeId, UUID stampId, UUID userId) {
        CashStamp stamp = stamps.findByIdAndStoreId(stampId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pieczątki"));
        if (stamp.isRemoved()) {
            stamps.findByStoreIdAndRemovedAtIsNull(storeId).ifPresent(current -> {
                current.remove(users.getReferenceById(userId));
                stamps.flush();
            });
            stamp.restore();
        }
        return settings(storeId);
    }

    @Transactional(readOnly = true)
    public DocumentService.ExportFile stampFile(UUID storeId, UUID stampId) {
        CashStamp stamp = stamps.findByIdAndStoreId(stampId, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pieczątki"));
        return new DocumentService.ExportFile(stamp.getFileName(), stamp.getContentType(), stamp.getContent());
    }

    @Transactional
    public CashSettings setOpeningBalance(UUID storeId, CashOpeningBalanceRequest request, UUID userId) {
        documents.lock(lockKey(storeId));
        if (closings.existsByStoreId(storeId)) {
            throw ApiException.conflict("Są już zakończone raporty: stanu początkowego nie da się zmienić, bo "
                    + "zmieniłby zamknięte dni");
        }
        if (documents.existsByStoreIdAndIssueDateBeforeAndCancelledAtIsNull(storeId, request.date())) {
            throw ApiException.conflict("Są dokumenty sprzed tej daty: stan początkowy musi być z dnia przed pierwszym "
                    + "dokumentem");
        }
        AppUser user = users.getReferenceById(userId);
        openings.findByStoreIdAndRemovedAtIsNull(storeId).ifPresent(previous -> {
            previous.remove(user);
            openings.flush();
        });
        openings.save(new CashOpeningBalance(storeId, request.date(), request.amount(), user));
        openings.flush();
        ledger(storeId).requireNonNegative();
        return settings(storeId);
    }

    // ---------------------------------------------------------------- pomocnicze

    static String lockKey(UUID storeId) {
        return "cash:" + storeId;
    }

    private void requireOpenDay(UUID storeId, LocalDate day, String action) {
        closings.lastClosedDate(storeId).ifPresent(last -> {
            if (!day.isAfter(last)) {
                throw ApiException.conflict("Raport kasowy z dnia " + last.format(DAY) + " jest zakończony: nie da się "
                        + action + " ani we wcześniejszych dniach");
            }
        });
    }

    private CashDocument find(UUID storeId, UUID id) {
        return documents.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono dokumentu kasowego"));
    }

    private Ledger ledger(UUID storeId) {
        CashOpeningBalance opening = openings.findByStoreIdAndRemovedAtIsNull(storeId).orElse(null);
        return new Ledger(opening, documents.findAllInOrder(storeId));
    }

    String userName(UUID userId) {
        return users.findById(userId).map(AppUser::getDisplayName).orElse("");
    }

    /** Numer i adres sklepu do stopki wydruku (nazwa sklepu w aplikacji jest wewnętrzna i nie trafia na dokumenty). */
    String storeLine(UUID storeId) {
        Store store = stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
        String address = store.getAddress() == null || store.getAddress().isBlank() ? "" : ", " + store.getAddress();
        return "Sklep " + store.getNumber() + address;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Stan początkowy i wszystkie dokumenty sklepu w kolejności raportu. */
    private record Ledger(CashOpeningBalance opening, List<CashDocument> documents) {

        BigDecimal start() {
            return opening == null ? BigDecimal.ZERO : opening.getAmount();
        }

        /** Stan kasy na początek dnia. */
        BigDecimal balanceBefore(LocalDate day) {
            if (opening != null && day.isBefore(opening.getBalanceDate())) {
                return BigDecimal.ZERO;
            }
            BigDecimal balance = start();
            for (CashDocument document : documents) {
                if (document.getIssueDate().isBefore(day)) {
                    balance = balance.add(document.signedAmount());
                }
            }
            return balance;
        }

        /** Stan kasy po każdym dokumencie nie może być ujemny (409 z dokumentem, przy którym spada). */
        void requireNonNegative() {
            BigDecimal balance = start();
            for (CashDocument document : documents) {
                balance = balance.add(document.signedAmount());
                if (balance.signum() < 0) {
                    throw ApiException.conflict("Brak gotówki w kasie: po dokumencie " + document.getNumber() + " z "
                            + document.getIssueDate().format(DAY) + " stan kasy wyniósłby "
                            + String.format(Locale.forLanguageTag("pl-PL"), "%,.2f zł", balance));
                }
            }
        }
    }
}
