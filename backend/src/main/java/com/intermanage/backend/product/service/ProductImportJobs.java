package com.intermanage.backend.product.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ImportSlots;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.product.dto.ProductImportJobResponse;
import com.intermanage.backend.product.dto.ProductImportJobResponse.Phase;
import com.intermanage.backend.product.dto.ProductImportJobResponse.Status;
import com.intermanage.backend.product.dto.ProductImportResponse;

/**
 * Importy kartoteki w tle. Raport dużego sklepu (150-200 tys. artykułów, pierwszy import zakłada wszystkie) scala się
 * kilka minut, a Cloudflare Tunnel zrywa żądanie po 100 s, więc wgranie od razu zwraca zadanie, a przeglądarka pyta
 * o jego stan. Zadania są w pamięci (jeden backend): po restarcie przepadają, a transakcja przerwanego importu się
 * wycofuje, więc raport trzeba wgrać ponownie. Naraz idzie tyle importów, ile miejsc ({@link ImportSlots}).
 */
@Component
public class ProductImportJobs {

    private static final Logger log = LoggerFactory.getLogger(ProductImportJobs.class);

    /** Tyle zakończone zadanie czeka, aż przeglądarka odbierze wynik. */
    private static final Duration KEEP_FINISHED = Duration.ofHours(1);

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ImportSlots slots;

    public ProductImportJobs(ImportSlots slots) {
        this.slots = slots;
    }

    /** Postęp importu podawany przez wątek importu, czytany przy pytaniu o stan. */
    public interface Progress {

        /** Przeczytana część pliku, 0-1. */
        void reading(double part);

        void saving(int processedRows, int totalRows);

        void finishing();
    }

    /** Uruchamia import w wirtualnym wątku; w sklepie najwyżej jeden naraz. */
    public synchronized ProductImportJobResponse start(UUID storeId, String fileName, Runnable cleanup,
            Function<Progress, ProductImportResponse> work) {
        forgetOld();
        Job running = jobs.values().stream()
                .filter(job -> job.storeId.equals(storeId) && job.status == Status.RUNNING)
                .findFirst().orElse(null);
        if (running != null) {
            cleanup.run();
            throw ApiException.conflict("Trwa import pliku " + running.fileName
                    + " do tego sklepu. Poczekaj, aż się skończy.");
        }
        Job job = new Job(UUID.randomUUID(), storeId, fileName);
        jobs.put(job.id, job);
        Thread.ofVirtual().name("product-import-" + storeId).start(() -> run(job, cleanup, work));
        return job.response();
    }

    /** Stan zadania; nieudane rzuca błąd importu, tak jak zrobiłoby to zwykłe wgranie. */
    public ProductImportJobResponse get(UUID storeId, UUID jobId) {
        Job job = jobs.get(jobId);
        if (job == null || !job.storeId.equals(storeId)) {
            throw ApiException.notFound("Nie znaleziono importu. Jeśli trwał podczas restartu serwera, wgraj plik ponownie.");
        }
        if (job.status == Status.FAILED) {
            throw job.error;
        }
        return job.response();
    }

    private void run(Job job, Runnable cleanup, Function<Progress, ProductImportResponse> work) {
        try {
            job.finish(slots.call(job.fileName + " sklepu " + job.storeId, () -> job.phase = Phase.READING,
                    () -> work.apply(job)), null);
        } catch (ApiException e) {
            job.finish(null, e);
        } catch (RuntimeException | Error e) {
            log.error("Import kartoteki {} do sklepu {} nie powiódł się", job.fileName, job.storeId, e);
            job.finish(null, new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Import nie powiódł się z powodu błędu serwera. Nic nie zostało zapisane, spróbuj ponownie."));
        } finally {
            cleanup.run();
        }
    }

    private void forgetOld() {
        Instant limit = Instant.now().minus(KEEP_FINISHED);
        jobs.values().removeIf(job -> job.finishedAt != null && job.finishedAt.isBefore(limit));
    }

    private static final class Job implements Progress {

        private final UUID id;
        private final UUID storeId;
        private final String fileName;
        private final Instant startedAt = Instant.now();
        private volatile Status status = Status.RUNNING;
        private volatile ProductImportResponse result;
        private volatile ApiException error;
        private volatile Instant finishedAt;
        private volatile Phase phase = Phase.WAITING;
        private volatile int percent;
        private volatile int processedRows;
        private volatile int totalRows;

        Job(UUID id, UUID storeId, String fileName) {
            this.id = id;
            this.storeId = storeId;
            this.fileName = fileName;
        }

        @Override
        public void reading(double part) {
            percent = (int) (part * 30);
        }

        @Override
        public void saving(int processedRows, int totalRows) {
            this.processedRows = processedRows;
            this.totalRows = totalRows;
            phase = Phase.SAVING;
            percent = 30 + (int) (65L * processedRows / Math.max(1, totalRows));
        }

        @Override
        public void finishing() {
            phase = Phase.FINISHING;
            percent = 95;
        }

        void finish(ProductImportResponse result, ApiException error) {
            if (error == null) {
                percent = 100;
            }
            this.result = result;
            this.error = error;
            this.finishedAt = Instant.now();
            this.status = error != null ? Status.FAILED : Status.DONE;
        }

        ProductImportJobResponse response() {
            return new ProductImportJobResponse(id, fileName, status, phase, percent, processedRows, totalRows, startedAt,
                    result);
        }
    }
}
