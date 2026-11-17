package com.intermanage.backend.staff.component;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.push.component.ExpoPushClient;
import com.intermanage.backend.staff.service.StaffTimeAnomalyService;

/**
 * Co {@code app.push.anomaly-interval} powiadamia pracowników z aplikacją o nowych różnicach między odbiciami a
 * grafikiem ({@link StaffTimeAnomalyService#notifyNew}). Działa tylko z włączonymi powiadomieniami
 * ({@code app.push.enabled}, w deploy/docker-compose.yml), na wirtualnym wątku, najwyżej jeden przebieg naraz.
 */
@Component
public class TimeAnomalyJob {

    private static final Logger log = LoggerFactory.getLogger(TimeAnomalyJob.class);

    private final StaffTimeAnomalyService anomalies;
    private final ExpoPushClient push;
    private final AtomicBoolean running = new AtomicBoolean();

    public TimeAnomalyJob(StaffTimeAnomalyService anomalies, ExpoPushClient push) {
        this.anomalies = anomalies;
        this.push = push;
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "${app.push.anomaly-interval}")
    public void schedule() {
        if (push.enabled() && running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("time-anomalies").start(() -> {
                try {
                    int sent = anomalies.notifyNew();
                    if (sent > 0) {
                        log.info("Powiadomienia o różnicach w czasie pracy: {}", sent);
                    }
                } catch (Exception e) {
                    log.warn("Powiadomienia o różnicach w czasie pracy nie powiodły się", e);
                } finally {
                    running.set(false);
                }
            });
        }
    }
}
