package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.intermanage.backend.entity.OrderUnit;

/** Pozycja zamówienia; {@code quantity} różni się od {@code orderedQuantity}, gdy dostawca ją poprawił. */
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
        BigDecimal quantity) {
}
