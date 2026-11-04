package com.intermanage.backend.leaflet.dto;

import java.time.Instant;
import java.util.UUID;

/** Wgrana gazetka na liście do otwarcia. */
public record LeafletSummary(
        UUID id,
        int year,
        int week,
        String fileName,
        int lineCount,
        Instant uploadedAt,
        String uploadedBy) {
}
