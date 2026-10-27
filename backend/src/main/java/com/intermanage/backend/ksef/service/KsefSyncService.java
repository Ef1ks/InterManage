package com.intermanage.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.component.KsefClient;
import com.intermanage.backend.component.KsefRateLimiter;
import com.intermanage.backend.dto.KsefInvoiceMetadata;
import com.intermanage.backend.entity.KsefConnection;
import com.intermanage.backend.entity.KsefInvoice;
import com.intermanage.backend.entity.KsefSyncState;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.repository.KsefConnectionRepository;
import com.intermanage.backend.repository.KsefInvoiceRepository;
import com.intermanage.backend.repository.KsefSyncStateRepository;

/**
 * Pobiera faktury zakupowe firmy (NIP z połączenia) z KSeF przyrostowo, po dacie trwałego zapisu rosnąco (tak zaleca
 * KSeF): od {@link KsefSyncState#getSyncCursor()} (za pierwszym razem od {@code app.ksef.sync-from}) w oknach do 90
 * dni, bo KSeF przyjmuje najwyżej 100. Faktury są wspólne dla sklepów firmy, więc pobieranie idzie raz na NIP, a nowy
 * sklep z połączeniem tej firmy od razu je widzi. Scalanie po numerze KSeF - ponowne pobranie niczego nie dubluje.
 * Zapytań o faktury jest w KSeF mało (16 na minutę, 20 na godzinę na kontekst), więc idą przez
 * {@link KsefRateLimiter} (z historią w bazie, więc restart jej nie zeruje): krótkie limity przeczekuje, a gdy skończy
 * się godzinowy (albo KSeF odpowie 429), postęp zostaje zapisany i następne pobranie zaczyna od tego miejsca. Strona
 * to 250 faktur, więc godzina limitu to kilka tysięcy faktur; większa zaległość schodzi w kolejnych pobraniach. Co {@code app.ksef.sync-interval} pobieranie rusza
 * samo dla firm z działającym połączeniem.
 */
@Service
public class KsefSyncService {

    private static final Logger log = LoggerFactory.getLogger(KsefSyncService.class);
    private static final Duration WINDOW = Duration.ofDays(90);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    private final TransactionTemplate transaction;
    private final KsefConnectionRepository connections;
    private final KsefSyncStateRepository states;
    private final KsefInvoiceRepository invoices;
    private final KsefSessionService sessions;
    private final KsefClient client;
    private final KsefRateLimiter limiter;
    private final Instant syncFrom;
    private final boolean scheduled;
    /** Trwające pobierania: jedno na firmę naraz (przyciski sklepów i harmonogram mogą się spotkać). */
    private final Map<String, Progress> running = new ConcurrentHashMap<>();

    public KsefSyncService(TransactionTemplate transaction, KsefConnectionRepository connections,
            KsefSyncStateRepository states, KsefInvoiceRepository invoices, KsefSessionService sessions,
            KsefClient client, KsefRateLimiter limiter, @Value("${app.ksef.sync-from}") LocalDate syncFrom,
            @Value("${app.ksef.sync-enabled}") boolean scheduled) {
        this.transaction = transaction;
        this.connections = connections;
        this.states = states;
        this.invoices = invoices;
        this.sessions = sessions;
        this.client = client;
        this.limiter = limiter;
        this.syncFrom = syncFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        this.scheduled = scheduled;
    }

    /** Każda firma raz, połączeniem z udanym testem logowania (albo dowolnym, jeśli poprzednie pobranie się udało). */
    @Scheduled(fixedDelayString = "${app.ksef.sync-interval}", initialDelayString = "PT2M")
    public void syncAll() {
        if (!scheduled) {
            return;
        }
        List<String> nips = connections.findAll().stream().map(KsefConnection::getNip).distinct().toList();
        for (String nip : nips) {
            KsefConnection connection = connections.findByNip(nip).getFirst();
            boolean syncedBefore = states.findById(nip).map(KsefSyncState::getSyncOk).orElse(false);
            if (!Boolean.TRUE.equals(connection.getTestOk()) && !syncedBefore) {
                continue;
            }
            Progress progress = new Progress();
            if (running.putIfAbsent(nip, progress) != null) {
                continue;
            }
            try {
                run(connection, progress);
            } catch (RuntimeException e) {
                log.warn("Automatyczne pobieranie faktur KSeF dla NIP {} nie powiodło się: {}", nip, e.getMessage());
            } finally {
                running.remove(nip);
            }
        }
    }

    /** Postęp trwającego pobierania: od kiedy i ile nowych faktur już zapisano. */
    public static final class Progress {

        private final Instant startedAt = Instant.now();
        private volatile int created;

        public Instant startedAt() {
            return startedAt;
        }

        public int created() {
            return created;
        }
    }

    /** Trwające pobieranie faktur firmy albo {@code null}. */
    public Progress progress(String nip) {
        return running.get(nip);
    }

    /**
     * Pobieranie na żądanie (przycisk) w tle, połączeniem sklepu: przy wielu fakturach trwa minuty, bo czeka na
     * limity KSeF. Postęp pokazuje {@link #progress(String)}, wynik zostaje zapisany dla firmy. Gdy pobieranie faktur
     * tej firmy już trwa (np. z innego sklepu), nic nie robi.
     */
    public void start(UUID storeId) {
        KsefConnection connection = connections.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("Sklep nie ma jeszcze połączenia z KSeF"));
        String nip = connection.getNip();
        Progress progress = new Progress();
        if (running.putIfAbsent(nip, progress) != null) {
            return;
        }
        Thread.ofVirtual().name("ksef-sync-" + nip).start(() -> {
            try {
                run(connection, progress);
            } catch (RuntimeException e) {
                log.info("Pobieranie faktur KSeF dla NIP {} zakończone błędem: {}", nip, e.getMessage());
            } finally {
                running.remove(nip);
            }
        });
    }

    private void run(KsefConnection connection, Progress counts) {
        String nip = connection.getNip();
        Instant saved = states.findById(nip).map(KsefSyncState::getSyncCursor).orElse(null);
        Instant cursor = saved != null ? saved : syncFrom;
        try {
            String token = sessions.accessToken(connection);
            Instant now = Instant.now();
            while (cursor.isBefore(now)) {
                Instant windowStart = cursor;
                Instant windowEnd = min(cursor.plus(WINDOW), now);
                Instant from = cursor;
                int pageOffset = 0;
                KsefInvoiceMetadata.Page page;
                while (true) {
                    page = query(nip, token, from, windowEnd, pageOffset);
                    save(nip, page.invoices(), counts);
                    if (!page.invoices().isEmpty()) {
                        // Faktury przychodzą rosnąco - wszystko przed ostatnią datą jest już zapisane.
                        cursor = page.invoices().getLast().permanentStorageDate().toInstant();
                        saveCursor(nip, cursor);
                    }
                    if (!page.hasMore()) {
                        break;
                    }
                    // Następna strona od daty ostatniej faktury, nie przez pageOffset: KSeF liczy głębokie strony
                    // coraz dłużej (timeouty). Faktury z tą samą datą przyjdą drugi raz i scalą się po numerze KSeF;
                    // offset tylko wtedy, gdy cała strona ma jedną datę.
                    if (cursor.isAfter(from)) {
                        from = cursor;
                        pageOffset = 0;
                    } else {
                        pageOffset++;
                    }
                }
                // Poniżej znacznika KSeF gwarantuje komplet; bez znacznika okno jest zamknięte do końca.
                Instant complete = page.permanentStorageHwmDate() != null
                        ? min(page.permanentStorageHwmDate().toInstant(), windowEnd) : windowEnd;
                cursor = complete.isAfter(cursor) ? complete : cursor;
                saveCursor(nip, cursor);
                // Bez postępu (KSeF nie potwierdził kompletu) - reszta przy następnym pobraniu.
                if (windowEnd.equals(now) || !cursor.isAfter(windowStart)) {
                    break;
                }
            }
            String message = counts.created == 0 ? "Brak nowych faktur"
                    : "Pobrano " + counts.created + " " + invoicesWord(counts.created);
            record(nip, true, message);
        } catch (KsefClient.KsefException e) {
            if (e.rateLimited()) {
                long minutes = Math.max(1, (e.retryAfter().toSeconds() + 59) / 60);
                String message = "Limit zapytań KSeF - pobrano " + counts.created + " " + invoicesWord(counts.created)
                        + ". Limit odnowi się za ok. " + minutes + " min, reszta pobierze się automatycznie.";
                record(nip, true, message);
                return;
            }
            if (e.temporary()) {
                String message = e.getMessage() + " - pobrano " + counts.created + " " + invoicesWord(counts.created)
                        + ", reszta przy następnym pobraniu.";
                record(nip, false, message);
                throw ApiException.badRequest("KSeF: " + message);
            }
            record(nip, false, e.getMessage());
            throw ApiException.badRequest("KSeF: " + e.getMessage());
        } catch (ApiException e) {
            record(nip, false, e.getMessage());
            throw e;
        }
    }

    /**
     * Strona metadanych w granicach limitów. Brak odpowiedzi albo błąd serwera KSeF powtarzamy kilka razy z przerwą;
     * każda próba liczy się do limitu zapytań.
     */
    private KsefInvoiceMetadata.Page query(String nip, String token, Instant from, Instant to, int offset) {
        for (int attempt = 1; ; attempt++) {
            try {
                return limiter.call(nip, KsefRateLimiter.Operation.METADATA,
                        () -> client.purchaseInvoices(token, from, to, offset));
            } catch (KsefClient.KsefException e) {
                if (!e.temporary() || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("KSeF nie odpowiedział (NIP {}, próba {}): {}", nip, attempt, e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY.multipliedBy(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Nowe faktury dochodzą, znane są uaktualniane (scalanie po numerze KSeF). */
    private void save(String nip, List<KsefInvoiceMetadata> page, Progress counts) {
        if (page.isEmpty()) {
            return;
        }
        transaction.executeWithoutResult(status -> {
            Map<String, KsefInvoice> existing = invoices.findByKsefNumbers(nip,
                            page.stream().map(KsefInvoiceMetadata::ksefNumber).toList())
                    .stream().collect(Collectors.toMap(KsefInvoice::getKsefNumber, Function.identity()));
            for (KsefInvoiceMetadata metadata : page) {
                KsefInvoice invoice = existing.get(metadata.ksefNumber());
                if (invoice == null) {
                    invoice = new KsefInvoice(nip, metadata);
                    existing.put(metadata.ksefNumber(), invoice);
                    invoices.save(invoice);
                    counts.created++;
                } else {
                    invoice.update(metadata);
                }
            }
        });
    }

    private void saveCursor(String nip, Instant cursor) {
        transaction.executeWithoutResult(status -> state(nip).setSyncCursor(cursor));
    }

    private void record(String nip, boolean ok, String message) {
        transaction.executeWithoutResult(status -> state(nip).recordSync(ok, KsefConnectionService.truncate(message)));
    }

    private KsefSyncState state(String nip) {
        return states.findById(nip).orElseGet(() -> states.save(new KsefSyncState(nip)));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** "1 fakturę", "3 faktury", "5 faktur". */
    static String invoicesWord(int count) {
        if (count == 1) {
            return "fakturę";
        }
        int lastTwo = count % 100;
        int last = count % 10;
        return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "faktury" : "faktur";
    }
}
