package com.intermanage.backend.deliorder.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.deliorder.entity.DeliOrderStatus;
import com.intermanage.backend.store.dto.StoreSummary;

/** Zamówienie na liście: {@code store} zamawia u {@code formStore}. */
public record DeliOrderSummaryResponse(
        UUID id,
        StoreSummary formStore,
        StoreSummary store,
        DeliOrderStatus status,
        LocalDate deliveryDate,
        LocalDate orderedDeliveryDate,
        Instant submittedAt,
        String submittedBy,
        int itemCount,
        /** Dostawca zmienił ilości po złożeniu zamówienia. */
        boolean corrected,
        /** Powstało z połączenia kilku zamówień. */
        boolean merged,
        /** Usunięte u zamawiającego albo u dostawcy (druga strona widzi je ze statusem "usunięte"). */
        Instant storeDeletedAt,
        Instant formStoreDeletedAt) {
}
