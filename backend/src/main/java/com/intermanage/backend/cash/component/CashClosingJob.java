package com.intermanage.backend.cash.component;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.intermanage.backend.cash.service.CashClosingService;
import com.intermanage.backend.common.component.StoreTime;

/**
 * Zakończenie raportów kasowych o 23:00 i zaległych dni co 10 minut. Tylko na produkcji
 * ({@code app.cash.closing-enabled}, w deploy/docker-compose.yml), bo lokalny backend z bazą produkcyjną w .env
 * zamykałby raporty produkcji.
 */
@Component
public class CashClosingJob {

    private final CashClosingService closings;
    private final boolean enabled;

    public CashClosingJob(CashClosingService closings, @Value("${app.cash.closing-enabled}") boolean enabled) {
        this.closings = closings;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Europe/Warsaw")
    public void run() {
        if (enabled) {
            closings.closeDue(LocalDateTime.now(StoreTime.ZONE));
        }
    }
}
