package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stała marża dostawcy: domyślna i wyjątki na nomenklaturze (kod z nazwą, od rejonu do podrodziny), kto i kiedy
 * zmienił ostatni raz.
 */
public record SupplierMarginResponse(UUID supplierId, String supplierName, String supplierCode,
        BigDecimal defaultPercent, List<Rule> rules, String updatedBy, Instant updatedAt) {

    public record Rule(String code, String name, BigDecimal marginPercent) {
    }
}
