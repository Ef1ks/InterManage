package com.intermanage.backend.basedelivery.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kody MEA sklepu: {@code pending} z wgranych dokumentów dostaw, których jeszcze nie potwierdzono jako wpisane
 * w Mercalys (z najnowszym bonem, w którym wystąpiły), i ostatnie potwierdzenia (także cofnięte, z {@code deletedAt}).
 */
public record MeaOverview(List<PendingCode> pending, List<Confirmation> confirmations) {

    public record PendingCode(String code, String deliveryNumber, LocalDate deliveryDate, long deliveryCount) {
    }

    public record Confirmation(UUID id, Instant confirmedAt, String confirmedBy, Instant deletedAt, String deletedBy,
            List<String> codes) {
    }
}
