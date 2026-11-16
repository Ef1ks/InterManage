package com.intermanage.backend.download.dto;

import java.time.Instant;
import java.util.UUID;

/** Skrót do folderu: {@code fileName} = pobrany plik, {@code target} = folder ze skrótu (puste = nieznany). */
public record ShortcutResponse(UUID id, String name, String fileName, String target, int sizeBytes,
        Instant uploadedAt, String uploadedBy) {
}
