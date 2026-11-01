package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

/**
 * Wynik działania zbiorczego: ile faktur zmieniło status, i znaczniki dodane / usunięte (do „Cofnij”,
 * {@link InvoiceBulkUndoRequest}).
 */
public record InvoiceBulkResult(int count, List<UUID> createdMarkIds, List<UUID> deletedMarkIds) {
}
