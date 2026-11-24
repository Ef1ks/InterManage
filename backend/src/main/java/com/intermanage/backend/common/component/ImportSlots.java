package com.intermanage.backend.common.component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Miejsca na importy raportów w tle, wspólne dla wszystkich sklepów. Import trzyma połączenie z bazą przez całą
 * transakcję (do kilku minut), a rano raporty wszystkich sklepów przychodzą naraz: bez limitu zajęłyby całą pulę
 * połączeń i aplikacja stanęłaby każdemu. Pozostałe czekają w kolejce według kolejności; ich pliki leżą na dysku.
 * Wołać tylko z wątku w tle, poza transakcją i bez trzymania drugiego miejsca (zakleszczenie).
 */
@Component
public class ImportSlots {

    private static final Logger log = LoggerFactory.getLogger(ImportSlots.class);

    private final Semaphore slots;

    public ImportSlots(@Value("${app.imports.max-parallel:3}") int maxParallel) {
        this.slots = new Semaphore(Math.max(1, maxParallel), true);
    }

    /** Wykonuje import, gdy zwolni się miejsce; {@code onStart} tuż przed nim. */
    public <T> T call(String name, Runnable onStart, Supplier<T> work) {
        if (!slots.tryAcquire()) {
            log.info("Import {} czeka na wolne miejsce (w kolejce przed nim: {})", name, slots.getQueueLength());
            try {
                slots.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Przerwano czekanie na import", e);
            }
        }
        try {
            onStart.run();
            return work.get();
        } finally {
            slots.release();
        }
    }
}
