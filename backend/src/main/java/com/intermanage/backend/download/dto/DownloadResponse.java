package com.intermanage.backend.download.dto;

import java.time.Instant;
import java.util.UUID;

/** Pozycja „Do pobrania” z obowiązującą wersją pliku ({@code file} puste = jeszcze bez pliku). */
public record DownloadResponse(UUID id, String title, String description, FileInfo file) {

    public record FileInfo(UUID id, String fileName, String version, int sizeBytes, String sha256,
            Instant uploadedAt, String uploadedBy) {
    }
}
