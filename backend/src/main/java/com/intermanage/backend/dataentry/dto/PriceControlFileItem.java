package com.intermanage.backend.dataentry.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Plik CTRLPRIX z historii (bez treści). {@code loadedAt} puste = program sklepu jeszcze go nie wczytał;
 * {@code processedCount} to N z końcówki .TraiteN (puste przy ręcznym oznaczeniu);
 * {@code macroWaiting}: zmianę wprowadzi sam komputer sklepu (agent).
 */
public record PriceControlFileItem(UUID id, String fileName, String supplierCode, String supplierName,
        String invoiceNumber, int lineCount, Instant createdAt, String createdBy, Instant loadedAt,
        boolean loadedManually, Integer processedCount, String loadedBy, boolean macroWaiting) {
}
