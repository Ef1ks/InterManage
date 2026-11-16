package com.intermanage.backend.mea.component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.mea.dto.MeaListResponse;
import com.intermanage.backend.mea.service.MeaDocumentService;
import com.intermanage.backend.mea.service.MeaLeafletService;
import com.intermanage.backend.mea.service.MeaService;

/**
 * Formularze MEA w tle: co {@code app.mea.sync-interval} lista MEA każdego sklepu z kluczem API (rok wstecz) zakłada
 * wpisy nowych MEA, potem nowe opisy gazetek z portalu ({@link MeaLeafletService#syncFromPortal}), a na końcu pobiera najwyżej {@code app.mea.sync-batch} formularzy z przerwą
 * {@code app.mea.download-delay}, najnowsze promocje najpierw. Pierwsze uzupełnienie roku (ok. 1,5 tys. formularzy)
 * idzie więc kilkoma przebiegami, bez obciążania portalu. Tylko na produkcji ({@code app.mea.sync-enabled},
 * w deploy/docker-compose.yml), bo lokalny backend z bazą produkcyjną w .env pisałby do niej. Przebieg na wirtualnym
 * wątku, najwyżej jeden naraz, żeby nie blokować innych zadań.
 */
@Component
public class MeaSyncJob {

    private static final Logger log = LoggerFactory.getLogger(MeaSyncJob.class);

    private final MeaService mea;
    private final MeaDocumentService documents;
    private final MeaLeafletService leaflets;
    private final boolean enabled;
    private final int batch;
    private final Duration delay;
    private final AtomicBoolean running = new AtomicBoolean();

    public MeaSyncJob(MeaService mea, MeaDocumentService documents, MeaLeafletService leaflets,
            @Value("${app.mea.sync-enabled}") boolean enabled, @Value("${app.mea.sync-batch}") int batch,
            @Value("${app.mea.download-delay}") Duration delay) {
        this.leaflets = leaflets;
        this.mea = mea;
        this.documents = documents;
        this.enabled = enabled;
        this.batch = batch;
        this.delay = delay;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${app.mea.sync-interval}")
    public void schedule() {
        if (enabled && running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("mea-sync").start(() -> {
                try {
                    run();
                } finally {
                    running.set(false);
                }
            });
        }
    }

    /** Jeden przebieg: listy sklepów, potem porcja formularzy. Zwraca liczbę pobranych. */
    public int run() {
        List<UUID> stores = mea.configuredStores();
        if (stores.isEmpty()) {
            return 0;
        }
        for (UUID storeId : stores) {
            try {
                MeaListResponse list = mea.fullList(storeId);
                documents.register(list.items());
            } catch (RuntimeException exception) {
                log.warn("MEA: lista sklepu {} niedostępna: {}", storeId, exception.getMessage());
            }
        }
        // Formularz i opis gazetki są jedne w sieci, więc pobieramy sesją pierwszego sklepu z kluczem.
        UUID storeId = stores.get(0);
        try {
            int leaflets = this.leaflets.syncFromPortal(storeId);
            if (leaflets > 0) {
                log.info("MEA: pobrano {} opisów gazetek", leaflets);
            }
        } catch (RuntimeException exception) {
            log.warn("MEA: opisy gazetek niedostępne: {}", exception.getMessage());
        }
        int fetched = 0;
        for (String number : documents.pending(batch)) {
            try {
                documents.fetch(storeId, number);
                fetched++;
            } catch (MurphysoftClient.Unavailable exception) {
                log.warn("MEA {}: {}", number, exception.getMessage());
            } catch (RuntimeException exception) {
                log.warn("MEA {}: błąd zapisu formularza", number, exception);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (fetched > 0) {
            log.info("MEA: pobrano {} formularzy", fetched);
        }
        return fetched;
    }
}
