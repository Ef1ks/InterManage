package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Faktura w kolejce do zamknięcia; {@code generatedAt}: poszła już do skryptu. */
public record InvoiceClosingItem(UUID id, String supplierCode, String documentNumber, String supplierName,
        LocalDate documentDate, BigDecimal netValue, Instant createdAt, Instant generatedAt) {
}
