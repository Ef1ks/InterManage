package com.intermanage.backend.importguide.dto;

import java.time.Instant;

import com.intermanage.backend.importguide.entity.ImportReportKind;

/** Instrukcja do raportu: jak wygenerować plik i w jakim folderze go szukać. */
public record ImportGuideResponse(ImportReportKind kind, String content, String folderPath, String createdBy,
        Instant createdAt) {
}
