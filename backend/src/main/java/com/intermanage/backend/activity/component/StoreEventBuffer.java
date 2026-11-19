package com.intermanage.backend.activity.component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bufor dziennika sklepu: żądanie tylko wkłada zdarzenie do kolejki w pamięci, a wątek w tle zapisuje paczki do bazy.
 * Gdy baza nie odpowiada, paczka trafia do pliku na dysku (wiersz JSON na zdarzenie, {@code app.store-events.spool-dir}),
 * a po powrocie połączenia plik jest odtwarzany do bazy. Tak samo przy pełnej kolejce i przy zatrzymaniu backendu, więc
 * zdarzenie nie ginie przy utracie połączenia. Kafka nie jest potrzebna: backend jest jeden, a po jej awarii byłby
 * ten sam problem. Wpis już zapisany (powtórka pliku po zerwanym potwierdzeniu) jest pomijany po id.
 *
 * <p>Logi tego pakietu nie trafiają do dziennika ({@link StoreEventLogAppender}), więc błąd zapisu nie zapętla się.
 */
@Component
public class StoreEventBuffer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StoreEventBuffer.class);
    private static final int BATCH = 500;
    private static final String SPOOL_PREFIX = "spool-";
    private static final String SPOOL_SUFFIX = ".jsonl";
    /** Plik innego procesu odtwarzamy dopiero, gdy nikt do niego nie pisze. */
    private static final Duration FOREIGN_SPOOL_AGE = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final StoreEventWriter writer;
    private final JsonMapper json;
    private final boolean enabled;
    private final Path spoolDir;
    private final BlockingQueue<StoreEventRecord> queue;
    /** Zdarzenia przyjęte, a jeszcze niezapisane w bazie ani w pliku. */
    private final AtomicInteger pending = new AtomicInteger();
    private final ReentrantLock spoolLock = new ReentrantLock();
    private final Path ownSpool;

    private volatile boolean running;
    private volatile Thread thread;
    private volatile boolean spooled;

    public StoreEventBuffer(StoreEventWriter writer, JsonMapper json,
            @Value("${app.store-events.enabled:true}") boolean enabled,
            @Value("${app.store-events.spool-dir:${java.io.tmpdir}/intermanage-store-events}") String spoolDir,
            @Value("${app.store-events.queue-size:20000}") int queueSize) {
        this.writer = writer;
        this.json = json;
        this.enabled = enabled;
        this.spoolDir = Path.of(spoolDir);
        this.queue = new ArrayBlockingQueue<>(queueSize);
        this.ownSpool = this.spoolDir.resolve(SPOOL_PREFIX + UUID.randomUUID() + SPOOL_SUFFIX);
    }

    /** Przyjmuje zdarzenie bez czekania na bazę. Przy pełnej kolejce zdarzenie idzie od razu do pliku. */
    public void record(StoreEventRecord event) {
        if (!enabled) {
            return;
        }
        pending.incrementAndGet();
        if (!queue.offer(event)) {
            spool(List.of(event));
            pending.decrementAndGet();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Czeka, aż przyjęte zdarzenia będą w bazie albo w pliku (testy i zatrzymanie backendu). */
    public void flush(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (pending.get() > 0 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("store-events").start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Czego wątek nie zdążył zapisać, zostaje w pliku do następnego startu.
        List<StoreEventRecord> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            spool(rest);
            pending.addAndGet(-rest.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Zatrzymanie po kontrolerach i filtrach, start przed nimi (domyślna faza to {@code Integer.MAX_VALUE}). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        Duration backoff = Duration.ofSeconds(1);
        Instant nextReplay = Instant.now();
        while (running) {
            List<StoreEventRecord> batch = new ArrayList<>();
            try {
                StoreEventRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH - 1);
                }
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            }
            if (!batch.isEmpty()) {
                try {
                    save(batch);
                    backoff = Duration.ofSeconds(1);
                } catch (RuntimeException e) {
                    log.warn("Dziennik sklepu: baza nie przyjęła {} zdarzeń, zostają w pliku: {}", batch.size(),
                            e.getMessage());
                    spool(batch);
                    nextReplay = Instant.now().plus(backoff);
                    backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF
                            : backoff.multipliedBy(2);
                } finally {
                    pending.addAndGet(-batch.size());
                }
            }
            if (Instant.now().isAfter(nextReplay)) {
                nextReplay = Instant.now().plus(spooled ? backoff : MAX_BACKOFF);
                replaySpool();
            }
        }
    }

    /** Zapis paczki; przy zdarzeniu już zapisanym (powtórka pliku) po jednym, z pominięciem obecnych. */
    private void save(List<StoreEventRecord> batch) {
        try {
            writer.save(batch);
        } catch (DataIntegrityViolationException e) {
            writer.saveMissing(batch);
        }
    }

    private void spool(List<StoreEventRecord> events) {
        spoolLock.lock();
        try {
            Files.createDirectories(spoolDir);
            StringBuilder lines = new StringBuilder();
            for (StoreEventRecord event : events) {
                lines.append(json.writeValueAsString(event)).append('\n');
            }
            Files.writeString(ownSpool, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            spooled = true;
        } catch (IOException | RuntimeException e) {
            log.error("Dziennik sklepu: nie udało się zapisać {} zdarzeń w pliku {}", events.size(), ownSpool, e);
        } finally {
            spoolLock.unlock();
        }
    }

    /** Odtwarza pliki z dysku (swój i pozostawione przez wcześniejsze uruchomienia). */
    private void replaySpool() {
        if (!Files.isDirectory(spoolDir)) {
            spooled = false;
            return;
        }
        boolean left = false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(spoolDir, SPOOL_PREFIX + "*" + SPOOL_SUFFIX)) {
            for (Path file : files) {
                if (!file.equals(ownSpool) && Files.getLastModifiedTime(file).toInstant()
                        .isAfter(Instant.now().minus(FOREIGN_SPOOL_AGE))) {
                    left = true;
                    continue;
                }
                if (!replay(file)) {
                    left = true;
                    break;
                }
            }
        } catch (IOException e) {
            log.warn("Dziennik sklepu: nie udało się odczytać folderu {}: {}", spoolDir, e.getMessage());
            left = true;
        }
        spooled = left;
    }

    /** Plik jest przenoszony przed odczytem, więc nowe zdarzenia lądują w nowym pliku. Fałsz = baza dalej nie działa. */
    private boolean replay(Path file) throws IOException {
        // Nazwa pasuje do wzorca plików, więc po awarii w trakcie odtwarzania następny start go podejmie.
        Path taken = file.resolveSibling(SPOOL_PREFIX + "replay-" + UUID.randomUUID() + SPOOL_SUFFIX);
        spoolLock.lock();
        try {
            Files.move(file, taken, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return true;
        } finally {
            spoolLock.unlock();
        }
        List<StoreEventRecord> events = new ArrayList<>();
        for (String line : Files.readAllLines(taken, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                try {
                    events.add(json.readValue(line, StoreEventRecord.class));
                } catch (RuntimeException e) {
                    log.warn("Dziennik sklepu: pominięto nieczytelny wiersz pliku {}", file);
                }
            }
        }
        try {
            for (int from = 0; from < events.size(); from += BATCH) {
                save(events.subList(from, Math.min(events.size(), from + BATCH)));
            }
        } catch (RuntimeException e) {
            // Całość wraca do pliku; zapisane już wiersze powtórka pominie po id.
            spool(events);
            Files.deleteIfExists(taken);
            return false;
        }
        Files.deleteIfExists(taken);
        if (!events.isEmpty()) {
            log.info("Dziennik sklepu: odtworzono z pliku {} zdarzeń", events.size());
        }
        return true;
    }
}
