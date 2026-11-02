package com.intermanage.backend.dataentry.dto;

import java.time.Instant;
import java.util.UUID;

/** Plik CTRLPRIX z historii (bez treści). */
public record PriceControlFileItem(UUID id, String fileName, String supplierCode, String supplierName,
        String invoiceNumber, int lineCount, Instant createdAt, String createdBy) {
}
