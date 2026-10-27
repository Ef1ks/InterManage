package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Produkt z kartoteki sklepu w podpowiedziach wyszukiwarki. */
public record ProductSearchResponse(
        UUID id,
        String itm8,
        String ean,
        String name,
        String brand,
        String unit,
        BigDecimal salePrice,
        Boolean active) {
}
