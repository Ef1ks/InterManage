package com.intermanage.backend.ksef.dto;

import java.time.Instant;

/**
 * Połączenie sklepu z KSeF bez sekretów: NIP (kontekst), czyj certyfikat i do kiedy ważny, wynik ostatniego testu
 * logowania. Pobieranie faktur jest wspólne dla firmy - jego stan jest w nagłówku listy ({@link KsefOverview}).
 */
public record KsefConnectionResponse(
        String nip,
        String certificateSubject,
        Instant certificateValidTo,
        String updatedBy,
        Instant updatedAt,
        Instant testedAt,
        Boolean testOk,
        String testMessage) {
}
