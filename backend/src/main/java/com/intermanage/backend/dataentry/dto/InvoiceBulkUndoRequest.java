package com.intermanage.backend.dataentry.dto;

import java.util.List;
import java.util.UUID;

/** Cofnięcie działania zbiorczego: dodane znaczniki dostają znacznik usunięcia, usunięte wracają jako nowe. */
public record InvoiceBulkUndoRequest(List<UUID> createdMarkIds, List<UUID> deletedMarkIds) {
}
