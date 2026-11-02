package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Stała cena sprzedaży brutto dostawcy z listy dostawców sklepu. */
public record SupplierSalePriceResponse(UUID supplierId, String supplierName, String supplierCode,
        BigDecimal salePrice, String createdBy, Instant createdAt) {
}
