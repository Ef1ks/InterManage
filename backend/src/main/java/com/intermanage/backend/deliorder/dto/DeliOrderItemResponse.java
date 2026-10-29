package com.intermanage.backend.deliorder.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.deliorder.entity.OrderUnit;

/**
 * Pozycja zamówienia; {@code quantity} różni się od {@code orderedQuantity}, gdy dostawca ją poprawił.
 * {@code addedAt} / {@code addedBy}: pozycja dopisana po złożeniu zamówienia.
 */
public record DeliOrderItemResponse(
        UUID id,
        UUID productId,
        String itm8,
        String ean,
        String name,
        String brand,
        String sectionName,
        String categoryName,
        OrderUnit unit,
        BigDecimal orderedQuantity,
        BigDecimal quantity,
        Instant addedAt,
        String addedBy) {
}
