package com.intermanage.backend.download.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.download.entity.DownloadChangeKind;

/**
 * Pozycja „Do pobrania” z obowiązującą wersją pliku ({@code file} puste = jeszcze bez pliku). {@code newVersion}:
 * zalogowane konto nie pobrało tej wersji ani nie potwierdziło, że ją ma, a wersja ma powiadomienie.
 */
public record DownloadResponse(UUID id, String title, String description, FileInfo file, boolean newVersion) {

    public record FileInfo(UUID id, String fileName, String version, int sizeBytes, String sha256,
            Instant uploadedAt, String uploadedBy, boolean announce, List<Change> changes) {
    }

    public record Change(DownloadChangeKind kind, String text) {
    }
}
