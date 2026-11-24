package com.intermanage.backend.cadtop.component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.cadtop.service.CadTopService;
import com.intermanage.backend.mea.service.MeaService;

/**
 * TOP-y cadencier w tle: co {@code app.cad-top.sync-interval} lista portalu każdego sklepu z kluczem API (rejon sklepu
 * i nowe pliki), a raz dziennie plan bieżącego i następnego roku. Tylko na produkcji, razem z formularzami MEA
 * ({@code app.mea.sync-enabled}). Przebieg na wirtualnym wątku, najwyżej jeden naraz.
 */
@Component
public class CadTopJob {

    private static final Logger log = LoggerFactory.getLogger(CadTopJob.class);

    private final CadTopService tops;
    private final MeaService mea;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public CadTopJob(CadTopService tops, MeaService mea, @Value("${app.mea.sync-enabled}") boolean enabled) {
        this.tops = tops;
        this.mea = mea;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "${app.cad-top.sync-interval}")
    public void schedule() {
        if (enabled && running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("cad-top-sync").start(() -> {
                try {
                    run();
                } catch (RuntimeException exception) {
                    log.warn("TOP cadencier: przebieg nieudany", exception);
                } finally {
                    running.set(false);
                }
            });
        }
    }

    void run() {
        List<UUID> stores = mea.configuredStores();
        for (UUID storeId : stores) {
            try {
                tops.syncTops(storeId);
            } catch (RuntimeException exception) {
                log.warn("TOP cadencier sklepu {} niedostępne: {}", storeId, exception.getMessage());
            }
        }
        if (!stores.isEmpty()) {
            tops.syncPlans(stores.getFirst(), false);
        }
    }
}
