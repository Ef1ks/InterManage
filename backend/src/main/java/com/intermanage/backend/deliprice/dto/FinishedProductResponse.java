package com.intermanage.backend.deliprice.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gotowy wyrób z kartoteki (nomenklatury 24, 36, 46), dla którego liczy się kalkulację. {@code salePrice} to obecna
 * cena na półce; {@code calculationId}, gdy wyrób ma już kalkulację.
 */
public record FinishedProductResponse(
        UUID productId,
        String itm8,
        String ean,
        String name,
        String brand,
        String nomenclature,
        String unit,
        BigDecimal salePrice,
        UUID calculationId) {
}
