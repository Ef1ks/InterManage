package com.intermanage.backend.store.dto;

import java.time.Instant;
import java.util.UUID;

/** Plik w kolejce sklepu: {@code folderKey} = klucz folderu w przeglądarce, {@code folderLabel} = jego opis. */
public record FileQueueItemResponse(UUID id, String folderKey, String folderLabel, String fileName, int sizeBytes,
        Instant createdAt, String createdBy) {
}
