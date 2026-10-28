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
        /** Data z chwili złożenia; różna od {@code deliveryDate}, gdy ktoś ją zmienił. */
        LocalDate orderedDeliveryDate,
        Instant deliveryDateChangedAt,
        String deliveryDateChangedBy,
        Instant submittedAt,
        String submittedBy,
        /** Pierwsze otwarcie u dostawcy. */
        Instant readAt,
        String readBy,
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
        /** Zamówienie powstało z połączenia {@code mergedFrom}. */
        Instant mergedAt,
        String mergedBy,
        List<DeliOrderSummaryResponse> mergedFrom,
        /** Zamówienie weszło do połączonego {@code mergedIntoId}. */
        UUID mergedIntoId,
        /** Połączenie cofnięte - zamówienie nie obowiązuje, źródłowe wróciły na listy. */
        Instant splitAt,
        List<DeliOrderItemResponse> items,
        /** Dokumenty ruchu międzymagazynowego, na których jedzie zamówienie (przypina dostawca). */
        List<DeliOrderDocumentResponse> documents) {
}
