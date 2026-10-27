package com.intermanage.backend.deliorder.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.deliorder.entity.DeliOrderStatus;
import com.intermanage.backend.store.dto.StoreSummary;

public record DeliOrderResponse(
        UUID id,
        StoreSummary formStore,
        StoreSummary store,
        DeliOrderStatus status,
        LocalDate deliveryDate,
        Instant submittedAt,
        String submittedBy,
        Instant correctedAt,
        String correctedBy,
        Instant fulfilledAt,
        String fulfilledBy,
        /** Usunięte u zamawiającego (wycofane) - dane zostają, można je przywrócić. */
        Instant storeDeletedAt,
        String storeDeletedBy,
        /** Usunięte u dostawcy. */
        Instant formStoreDeletedAt,
        String formStoreDeletedBy,
        List<DeliOrderItemResponse> items,
        /** Dokumenty ruchu międzymagazynowego, na których jedzie zamówienie (przypina dostawca). */
        List<DeliOrderDocumentResponse> documents) {
}
