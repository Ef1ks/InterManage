package com.intermanage.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.entity.DeliOrderStatus;

/** Zamówienie na liście: {@code store} zamawia u {@code formStore}. */
public record DeliOrderSummaryResponse(
        UUID id,
        StoreSummary formStore,
        StoreSummary store,
        DeliOrderStatus status,
        LocalDate deliveryDate,
        Instant submittedAt,
        String submittedBy,
        int itemCount,
        /** Dostawca zmienił ilości po złożeniu zamówienia. */
        boolean corrected,
        /** Usunięte u zamawiającego albo u dostawcy (druga strona widzi je ze statusem "usunięte"). */
        Instant storeDeletedAt,
        Instant formStoreDeletedAt) {
}
