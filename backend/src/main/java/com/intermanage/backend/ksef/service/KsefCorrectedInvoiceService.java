package com.intermanage.backend.ksef.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.component.KsefRateLimiter;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceXml;
import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceXmlRepository;
import com.intermanage.backend.ksef.repository.KsefSkippedSupplierRepository;

/**
 * Faktury korygowane przez korekty z KSeF: metadane ich nie podają, więc XML każdej korekty pobiera się w tle (raz,
 * zostaje w {@code ksef_invoice_xml}) i zapisuje z niego {@link KsefInvoice#getCorrectedInvoices()}. Po nich korekta
 * łączy się z fakturą, a przez nią z przyjęciem PZ. Najpierw korekty z XML już w bazie (bez KSeF), potem od najnowszej
 * pobierane z KSeF, ale tylko do {@link #RESERVED_PER_HOUR} wolnych pobrań w godzinie limitu firmy, żeby zostały na
 * „Pobierz PDF” i porównania z fakturą. Pomija korekty bazy ({@link #IGNORED_SELLER_NIPS}, wiązane kwotowo) i
 * dostawców pominiętych we wszystkich sklepach firmy. Każda firma (NIP) osobno, bo każda ma własny limit.
 */
@Service
public class KsefCorrectedInvoiceService {

    /** SCA PR Polska (baza): jej korekt nie wiążemy z bonami, więc nie pobieramy ich XML. */
    public static final Set<String> IGNORED_SELLER_NIPS = Set.of("7821977018");
    /** Tyle pobrań faktur w godzinie limitu zostaje dla ludzi. */
    static final int RESERVED_PER_HOUR = 20;
    private static final int STORED_BATCH = 500;

    private static final Logger log = LoggerFactory.getLogger(KsefCorrectedInvoiceService.class);

    private final TransactionTemplate transaction;
    private final KsefConnectionRepository connections;
    private final KsefInvoiceRepository invoices;
    private final KsefInvoiceXmlRepository xmlFiles;
    private final KsefSkippedSupplierRepository skippedSuppliers;
    private final KsefSessionService sessions;
    private final KsefClient client;
    private final KsefRateLimiter limiter;
    private final KsefInvoiceReader reader;
    private final boolean scheduled;
    private final AtomicBoolean running = new AtomicBoolean();

    public KsefCorrectedInvoiceService(TransactionTemplate transaction, KsefConnectionRepository connections,
            KsefInvoiceRepository invoices, KsefInvoiceXmlRepository xmlFiles,
            KsefSkippedSupplierRepository skippedSuppliers, KsefSessionService sessions, KsefClient client,
            KsefRateLimiter limiter, KsefInvoiceReader reader, @Value("${app.ksef.sync-enabled}") boolean scheduled) {
        this.transaction = transaction;
        this.connections = connections;
        this.invoices = invoices;
        this.xmlFiles = xmlFiles;
        this.skippedSuppliers = skippedSuppliers;
        this.sessions = sessions;
        this.client = client;
        this.limiter = limiter;
        this.reader = reader;
        this.scheduled = scheduled;
    }

    /**
     * W osobnym wątku: czekanie na limit KSeF (do minuty na zapytanie) nie wstrzymuje innych zadań w tle. Kolejne
     * uruchomienie, gdy poprzednie jeszcze trwa, nic nie robi.
     */
    @Scheduled(fixedDelayString = "${app.ksef.corrections-interval}", initialDelayString = "PT3M")
    public void readAllInBackground() {
        if (!scheduled || !running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("ksef-corrections").start(() -> {
            try {
                readAll();
            } finally {
                running.set(false);
            }
        });
    }

    /** Każda firma z połączeniem osobno; błąd jednej nie zatrzymuje pozostałych. */
    public void readAll() {
        List<String> nips = connections.findAll().stream().map(KsefConnection::getNip).distinct().toList();
        for (String nip : nips) {
            try {
                read(nip);
            } catch (RuntimeException e) {
                log.warn("Odczyt faktur korygowanych z KSeF dla NIP {} nie powiódł się: {}", nip, e.getMessage());
            }
        }
    }

    /** Wynik jednego przebiegu dla firmy: ile korekt odczytano z XML w bazie, a ile pobrano z KSeF. */
    public record Result(int fromStored, int downloaded) {
    }

    public Result read(String nip) {
        Set<String> ignored = ignoredSellers(nip);
        int fromStored = 0;
        for (UUID id : invoices.findUnreadStoredCorrections(nip, ignored, PageRequest.of(0, STORED_BATCH))) {
            transaction.executeWithoutResult(status -> xmlFiles.findById(id)
                    .ifPresent(xml -> record(invoices.getReferenceById(id), xml.getContent())));
            fromStored++;
        }
        Optional<KsefConnection> connection = connections.findByNip(nip).stream()
                .filter(found -> Boolean.TRUE.equals(found.getTestOk())).findFirst();
        int budget = limiter.remaining(nip, KsefRateLimiter.Operation.INVOICE) - RESERVED_PER_HOUR;
        if (connection.isEmpty() || budget <= 0) {
            return new Result(fromStored, 0);
        }
        List<KsefInvoice> pending = invoices.findUnreadCorrectionsToDownload(nip, ignored, PageRequest.of(0, budget));
        if (pending.isEmpty()) {
            return new Result(fromStored, 0);
        }
        String token = sessions.accessToken(connection.get());
        int downloaded = 0;
        for (KsefInvoice invoice : pending) {
            byte[] content;
            try {
                content = limiter.call(nip, KsefRateLimiter.Operation.INVOICE,
                        () -> client.invoiceXml(token, invoice.getKsefNumber()));
            } catch (KsefClient.KsefException e) {
                if (e.rateLimited() || e.temporary()) {
                    // Limit albo chwilowy brak KSeF - reszta przy następnym przebiegu.
                    log.info("Odczyt korekt KSeF dla NIP {} wstrzymany: {}", nip, e.getMessage());
                    break;
                }
                transaction.executeWithoutResult(status -> invoices.findById(invoice.getId())
                        .ifPresent(found -> found.recordCorrected(Set.of(), truncate("KSeF: " + e.getMessage()))));
                continue;
            }
            transaction.executeWithoutResult(status -> {
                if (!xmlFiles.existsById(invoice.getId())) {
                    xmlFiles.save(new KsefInvoiceXml(invoice.getId(), content));
                }
                invoices.findById(invoice.getId()).ifPresent(found -> record(found, content));
            });
            downloaded++;
        }
        return new Result(fromStored, downloaded);
    }

    private void record(KsefInvoice invoice, byte[] xml) {
        try {
            Set<KsefInvoice.CorrectedRef> refs = reader.correctedInvoices(xml).stream()
                    .map(corrected -> new KsefInvoice.CorrectedRef(truncate(corrected.number(), 256),
                            truncate(corrected.ksefNumber(), 36), corrected.issueDate()))
                    .collect(Collectors.toSet());
            invoice.recordCorrected(refs, null);
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            invoice.recordCorrected(Set.of(), truncate("Nie udało się odczytać faktury: " + e.getMessage()));
        }
    }

    /** Baza i dostawcy pominięci w każdym sklepie firmy (pominięty tylko w jednym sklepie dalej się liczy). */
    private Set<String> ignoredSellers(String nip) {
        Set<String> ignored = new HashSet<>(IGNORED_SELLER_NIPS);
        Set<String> skippedEverywhere = null;
        for (UUID storeId : connections.findStoreIdsByNip(nip)) {
            Set<String> skipped = skippedSuppliers.findActive(storeId).stream().map(KsefSkippedSupplier::getNip)
                    .collect(Collectors.toSet());
            if (skippedEverywhere == null) {
                skippedEverywhere = skipped;
            } else {
                skippedEverywhere.retainAll(skipped);
            }
        }
        if (skippedEverywhere != null) {
            ignored.addAll(skippedEverywhere);
        }
        return ignored;
    }

    private static String truncate(String value) {
        return truncate(value, 500);
    }

    private static String truncate(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
