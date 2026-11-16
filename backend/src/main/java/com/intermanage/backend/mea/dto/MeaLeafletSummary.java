package com.intermanage.backend.mea.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Opis gazetki obowiązujący dla swojego roku i numeru; {@code fromPortal} = pobrany w tle z portalu (bez osoby), inaczej
 * wgrany ręcznie przez {@code uploadedBy}.
 */
public record MeaLeafletSummary(UUID id, int year, int week, String fileName, int lineCount, Instant uploadedAt,
        String uploadedBy, boolean fromPortal) {

    /** Wynik sprawdzenia portalu: ile nowych opisów pobrano i opisy po nim. */
    public record Sync(int downloaded, java.util.List<MeaLeafletSummary> leaflets) {
    }
}
