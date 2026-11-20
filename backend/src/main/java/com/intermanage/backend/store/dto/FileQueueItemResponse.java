package com.intermanage.backend.store.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Plik w kolejce sklepu: {@code folderKey} = klucz folderu w przeglądarce, {@code folderLabel} = jego opis,
 * {@code groupKey} / {@code groupLabel} = dokument (np. faktura), do którego należy, albo {@code null}.
 * {@code claimedBy}: kto właśnie zapisuje plik (agent sklepu albo osoba), {@code null}, gdy nikt albo przydział wygasł;
 * {@code failedAt} / {@code lastError}: ostatni nieudany zapis.
 */
public record FileQueueItemResponse(UUID id, String folderKey, String folderLabel, String groupKey,
        String groupLabel, String fileName, int sizeBytes, Instant createdAt, String createdBy, String claimedBy,
        Instant claimExpiresAt, Instant failedAt, String lastError) {
}
