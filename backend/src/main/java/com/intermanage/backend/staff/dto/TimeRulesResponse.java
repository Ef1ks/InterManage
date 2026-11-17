package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.entity.TimeRequestApprover;

/**
 * Zasady odbić w sklepie ({@code saved} = false: domyślne, bez zapisu), sklepy zaprzyjaźnione, którym można je
 * zaproponować, i oczekujące propozycje: {@code incoming} do tego sklepu, {@code outgoing} od niego.
 */
public record TimeRulesResponse(int roundingMinutes, int punchCooldownSeconds, TimeRequestApprover finalApprover,
        boolean saved, Instant savedAt, String savedBy, List<Partner> partners, List<Offer> incoming,
        List<Offer> outgoing) {

    public record Partner(UUID storeId, String number, String name) {
    }

    /** {@code storeId} i nazwa to druga strona: nadawca przy przychodzących, odbiorca przy wysłanych. */
    public record Offer(UUID id, UUID storeId, String storeNumber, String storeName, int roundingMinutes,
            int punchCooldownSeconds, TimeRequestApprover finalApprover, Instant createdAt, String createdBy) {
    }
}
