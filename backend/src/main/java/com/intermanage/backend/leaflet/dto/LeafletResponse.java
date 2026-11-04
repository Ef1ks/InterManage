package com.intermanage.backend.leaflet.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gazetka z pozycjami. {@code pendingMeaCodes}: kody MEA z gazetki, których jeszcze nie potwierdzono jako wpisane
 * w Mercalys (jak kody z dostaw z bazy).
 */
public record LeafletResponse(
        UUID id,
        int year,
        int week,
        String fileName,
        Instant uploadedAt,
        String uploadedBy,
        List<LeafletLineResponse> lines,
        List<String> pendingMeaCodes) {
}
