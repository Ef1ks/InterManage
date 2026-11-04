package com.intermanage.backend.basedelivery.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kody MEA sklepu: {@code pending} z wgranych dokumentów dostaw i gazetek, których jeszcze nie potwierdzono jako wpisane
 * w Mercalys (z najnowszym bonem, w którym wystąpiły), i ostatnie potwierdzenia (także cofnięte, z {@code deletedAt}).
 */
public record MeaOverview(List<PendingCode> pending, List<Confirmation> confirmations) {

    /**
     * Kod do wpisania z najnowszym dokumentem, w którym jest: bonem dostawy ({@code deliveryNumber}, data dostawy)
     * albo gazetką ({@code leaflet}, numer „44/2026”, początek promocji); {@code deliveryCount} liczy oba rodzaje.
     */
    public record PendingCode(String code, String deliveryNumber, LocalDate deliveryDate, long deliveryCount,
            boolean leaflet) {
    }

    public record Confirmation(UUID id, Instant confirmedAt, String confirmedBy, Instant deletedAt, String deletedBy,
            List<String> codes) {
    }
}
