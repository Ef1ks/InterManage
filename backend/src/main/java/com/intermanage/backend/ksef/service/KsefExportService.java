package com.intermanage.backend.ksef.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefExportCrypto;
import com.intermanage.backend.ksef.component.KsefInvoiceReader;
import com.intermanage.backend.ksef.component.KsefRateLimiter;
import com.intermanage.backend.ksef.dto.KsefInvoiceMetadata;
import com.intermanage.backend.ksef.entity.KsefConnection;
import com.intermanage.backend.ksef.entity.KsefInvoice;
import com.intermanage.backend.ksef.entity.KsefInvoiceXml;
import com.intermanage.backend.ksef.entity.KsefSyncState;
import com.intermanage.backend.ksef.repository.KsefConnectionRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceRepository;
import com.intermanage.backend.ksef.repository.KsefInvoiceXmlRepository;
import com.intermanage.backend.ksef.repository.KsefSyncStateRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * XML wszystkich faktur zakupowych firmy z eksportu paczek KSeF ({@code POST /invoices/exports}): paczka to do 10 tys.
 * faktur jednym zleceniem (zleceń 20 na godzinę), zamiast pobierania pojedynczo (64 na godzinę). Z każdego XML
 * zapisujemy adres dostawy z Podmiotów 3 (sklep faktur bez IDWew, np. bazy) i przy korekcie faktury korygowane
 * ({@link KsefCorrectedInvoiceService#readXml}); XML zostaje w {@code ksef_invoice_xml}, więc PDF i porównania nie
 * zużywają już limitu. Przyrostowo po dacie trwałego zapisu od {@link KsefSyncState#getExportCursor()} (za pierwszym
 * razem od {@code app.ksef.sync-from}), okna do 90 dni, paczka ucięta na limicie ciągnie się od daty ostatniej
 * faktury. Faktura z paczki, której jeszcze nie pobrało {@link KsefSyncService}, powstaje z {@code _metadata.json}.
 * Każda firma (NIP) osobno; co {@code app.ksef.export-interval}, gdy {@code app.ksef.sync-enabled}.
 */
@Service
public class KsefExportService {

    private static final Logger log = LoggerFactory.getLogger(KsefExportService.class);
    private static final Duration WINDOW = Duration.ofDays(90);
    /** Najwięcej eksportów w jednym przebiegu (limit KSeF: 20 zleceń na godzinę). */
    private static final int MAX_EXPORTS = 6;
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration WAIT = Duration.ofMinutes(20);
    /** Tyle XML-i w jednej transakcji zapisu. */
    private static final int BATCH = 200;
    private static final String METADATA_FILE = "_metadata.json";
    private static final int FILTER_AFTER_HWM = 420;

    private final TransactionTemplate transaction;
    private final KsefConnectionRepository connections;
    private final KsefSyncStateRepository states;
    private final KsefInvoiceRepository invoices;
    private final KsefInvoiceXmlRepository xmlFiles;
    private final KsefSessionService sessions;
    private final KsefSyncService syncService;
    private final KsefCorrectedInvoiceService xmlReading;
    private final KsefClient client;
    private final KsefRateLimiter limiter;
    private final KsefInvoiceReader reader;
    private final JsonMapper json;
    private final Instant syncFrom;
    private final boolean scheduled;
    private final AtomicBoolean running = new AtomicBoolean();

    public KsefExportService(TransactionTemplate transaction, KsefConnectionRepository connections,
            KsefSyncStateRepository states, KsefInvoiceRepository invoices, KsefInvoiceXmlRepository xmlFiles,
            KsefSessionService sessions, KsefSyncService syncService, KsefCorrectedInvoiceService xmlReading,
            KsefClient client, KsefRateLimiter limiter, KsefInvoiceReader reader, JsonMapper json,
            @Value("${app.ksef.sync-from}") LocalDate syncFrom, @Value("${app.ksef.sync-enabled}") boolean scheduled) {
        this.transaction = transaction;
        this.connections = connections;
        this.states = states;
        this.invoices = invoices;
        this.xmlFiles = xmlFiles;
        this.sessions = sessions;
        this.syncService = syncService;
        this.xmlReading = xmlReading;
        this.client = client;
        this.limiter = limiter;
        this.reader = reader;
        this.json = json;
        this.syncFrom = syncFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        this.scheduled = scheduled;
    }

    /** W osobnym wątku, bo eksport czeka na KSeF minutami; kolejne uruchomienie w trakcie nic nie robi. */
    @Scheduled(fixedDelayString = "${app.ksef.export-interval}", initialDelayString = "PT5M")
    public void exportAllInBackground() {
        if (!scheduled || !running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("ksef-export").start(() -> {
            try {
                exportAll();
            } finally {
                running.set(false);
            }
        });
    }

    /** Każda firma z działającym połączeniem osobno; błąd jednej nie zatrzymuje pozostałych. */
    public void exportAll() {
        List<String> nips = connections.findAll().stream().map(KsefConnection::getNip).distinct().toList();
        for (String nip : nips) {
            Optional<KsefConnection> connection = connections.findByNip(nip).stream()
                    .filter(found -> Boolean.TRUE.equals(found.getTestOk())).findFirst();
            if (connection.isEmpty()) {
                continue;
            }
            try {
                export(connection.get());
            } catch (RuntimeException e) {
                log.warn("Eksport paczek faktur KSeF dla NIP {} nie powiódł się: {}", nip, e.getMessage());
            }
        }
    }

    /** Wynik przebiegu dla firmy: ile faktur przyszło w paczkach i ile z nich było nowych w bazie. */
    public record Result(int invoices, int created) {
    }

    public Result export(KsefConnection connection) {
        String nip = connection.getNip();
        Instant saved = states.findById(nip).map(KsefSyncState::getExportCursor).orElse(null);
        Instant cursor = saved != null ? saved : syncFrom;
        int total = 0;
        int created = 0;
        try {
            for (int round = 0; round < MAX_EXPORTS; round++) {
                Instant now = Instant.now();
                if (!cursor.isBefore(now)) {
                    break;
                }
                Instant windowEnd = min(cursor.plus(WINDOW), now);
                Ready ready = runExport(connection, cursor, windowEnd);
                if (ready == null) {
                    break;
                }
                KsefClient.ExportStatus status = ready.status();
                Result read = readPackage(nip, ready);
                total += read.invoices();
                created += read.created();
                Instant next;
                if (status.truncated() && status.lastPermanentStorageDate() != null) {
                    next = status.lastPermanentStorageDate();
                } else {
                    next = status.permanentStorageHwmDate() != null
                            ? min(status.permanentStorageHwmDate(), windowEnd) : windowEnd;
                }
                if (!next.isAfter(cursor)) {
                    break;
                }
                cursor = next;
                Instant finalCursor = cursor;
                transaction.executeWithoutResult(tx -> state(nip).setExportCursor(finalCursor));
                if (!status.truncated() && windowEnd.equals(now)) {
                    break;
                }
            }
            record(nip, true, total == 0 ? "Paczki bez nowych faktur"
                    : "Odczytano " + total + " " + KsefSyncService.invoicesWord(total) + " z paczek KSeF");
        } catch (KsefClient.KsefException e) {
            String message = e.rateLimited() ? "Limit eksportów KSeF - reszta przy następnym przebiegu"
                    : e.getMessage();
            record(nip, e.rateLimited(), message + " (odczytano " + total + ")");
            if (!e.rateLimited() && !e.temporary()) {
                throw e;
            }
        }
        return new Result(total, created);
    }

    /** Gotowa paczka i klucz, którym są zaszyfrowane jej części. */
    private record Ready(KsefClient.ExportStatus status, KsefExportCrypto.PackageKey key) {
    }

    /**
     * Zlecenie i oczekiwanie na paczkę; {@code null}, gdy zakres jest już poza znacznikiem kompletności KSeF (nie ma
     * jeszcze czego eksportować).
     */
    private Ready runExport(KsefConnection connection, Instant from, Instant to) {
        String nip = connection.getNip();
        String token = sessions.accessToken(connection);
        KsefClient.PublicKey mfKey = client.symmetricKeyEncryptionKey();
        KsefExportCrypto.PackageKey key = KsefExportCrypto.newKey(mfKey.certificate());
        String reference = limiter.call(nip, KsefRateLimiter.Operation.EXPORT, () -> client.startPurchaseExport(
                token, from, to, key.encryptedKey(), key.initializationVector(), mfKey.publicKeyId()));
        Instant deadline = Instant.now().plus(WAIT);
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                sleep(POLL);
            }
            KsefClient.ExportStatus status = limiter.call(nip, KsefRateLimiter.Operation.EXPORT_STATUS,
                    () -> client.exportStatus(token, reference));
            if (status.ready()) {
                return new Ready(status, key);
            }
            if (status.code() == FILTER_AFTER_HWM) {
                return null;
            }
            if (!status.inProgress()) {
                throw new KsefClient.KsefException("Eksport paczki faktur: " + status.description(), null,
                        status.code() >= 500);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new KsefClient.KsefException("KSeF nie przygotował paczki faktur w " + WAIT.toMinutes()
                        + " min", null, true);
            }
        }
    }

    /**
     * Części paczki pobrane i odszyfrowane do pliku tymczasowego (paczka bywa duża), potem ZIP: najpierw
     * {@code _metadata.json} (faktury, których jeszcze nie ma w bazie), potem XML-e paczkami po {@link #BATCH}.
     */
    private Result readPackage(String nip, Ready ready) {
        if (ready.status().parts().isEmpty()) {
            return new Result(0, 0);
        }
        Path zip = null;
        try {
            zip = Files.createTempFile("ksef-export-", ".zip");
            try (OutputStream out = Files.newOutputStream(zip)) {
                for (KsefClient.ExportPart part : ready.status().parts()) {
                    KsefExportCrypto.decryptPart(client.exportPart(part), part.encryptedPartHash(), ready.key(), out);
                }
            }
            try (ZipFile file = new ZipFile(zip.toFile())) {
                Map<String, KsefInvoiceMetadata> metadata = metadata(file);
                int total = 0;
                int created = 0;
                Map<String, byte[]> batch = new LinkedHashMap<>();
                Enumeration<? extends ZipEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.toLowerCase().endsWith(".xml")) {
                        continue;
                    }
                    String ksefNumber = name.substring(name.lastIndexOf('/') + 1, name.length() - 4);
                    try (InputStream in = file.getInputStream(entry)) {
                        batch.put(ksefNumber, in.readAllBytes());
                    }
                    if (batch.size() >= BATCH) {
                        created += save(nip, batch, metadata);
                        total += batch.size();
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    created += save(nip, batch, metadata);
                    total += batch.size();
                }
                return new Result(total, created);
            }
        } catch (IOException e) {
            throw new KsefClient.KsefException("Nie udało się odczytać paczki faktur: " + e.getMessage(), null,
                    true);
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException e) {
                    log.warn("Nie udało się usunąć pliku paczki {}: {}", zip, e.getMessage());
                }
            }
        }
    }

    /** Metadane faktur paczki po numerze KSeF (pusto, gdy pliku nie ma albo jest nieczytelny). */
    private Map<String, KsefInvoiceMetadata> metadata(ZipFile file) {
        ZipEntry entry = file.getEntry(METADATA_FILE);
        if (entry == null) {
            return Map.of();
        }
        try (InputStream in = file.getInputStream(entry)) {
            JsonNode root = json.readTree(in);
            Map<String, KsefInvoiceMetadata> found = new HashMap<>();
            for (JsonNode invoice : root.path("invoices")) {
                KsefInvoiceMetadata metadata = json.treeToValue(invoice, KsefInvoiceMetadata.class);
                found.put(metadata.ksefNumber(), metadata);
            }
            return found;
        } catch (IOException | RuntimeException e) {
            log.warn("Nieczytelny plik {} w paczce faktur: {}", METADATA_FILE, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Jedna transakcja: brakujące faktury z metadanych, XML zapisany, jeśli go jeszcze nie ma (faktura w KSeF się nie
     * zmienia), adres dostawy i faktury korygowane odczytane od nowa. Zwraca liczbę nowych faktur.
     */
    private int save(String nip, Map<String, byte[]> batch, Map<String, KsefInvoiceMetadata> metadata) {
        Integer created = transaction.execute(tx -> {
            List<KsefInvoiceMetadata> missing = new ArrayList<>();
            Map<String, KsefInvoice> known = invoices.findByKsefNumbers(nip, List.copyOf(batch.keySet())).stream()
                    .collect(Collectors.toMap(KsefInvoice::getKsefNumber, Function.identity()));
            for (String ksefNumber : batch.keySet()) {
                if (!known.containsKey(ksefNumber) && metadata.containsKey(ksefNumber)) {
                    missing.add(metadata.get(ksefNumber));
                }
            }
            int added = missing.isEmpty() ? 0 : syncService.saveMetadata(nip, missing);
            if (added > 0) {
                invoices.flush();
                known = invoices.findByKsefNumbers(nip, List.copyOf(batch.keySet())).stream()
                        .collect(Collectors.toMap(KsefInvoice::getKsefNumber, Function.identity()));
            }
            for (Map.Entry<String, byte[]> xml : batch.entrySet()) {
                KsefInvoice invoice = known.get(xml.getKey());
                if (invoice == null) {
                    continue;
                }
                if (!xmlFiles.existsById(invoice.getId())) {
                    xmlFiles.save(new KsefInvoiceXml(invoice.getId(), xml.getValue(), depositNet(xml.getValue())));
                }
                xmlReading.readXml(invoice, xml.getValue());
            }
            return added;
        });
        return created != null ? created : 0;
    }

    private BigDecimal depositNet(byte[] xml) {
        try {
            return KsefInvoiceReader.depositNet(reader.read(xml).lines());
        } catch (KsefInvoiceReader.InvalidInvoiceException e) {
            return null;
        }
    }

    private void record(String nip, boolean ok, String message) {
        transaction.executeWithoutResult(tx -> state(nip).recordExport(ok, KsefConnectionService.truncate(message)));
    }

    private KsefSyncState state(String nip) {
        return states.findById(nip).orElseGet(() -> states.save(new KsefSyncState(nip)));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KsefClient.KsefException("Przerwano eksport paczki faktur", null);
        }
    }
}
