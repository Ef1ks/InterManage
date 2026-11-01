package com.intermanage.backend.backup.dto;

import java.time.Instant;
import java.util.List;

/**
 * Ustawienia kopii z historią. {@code googleConfigured} = serwer ma klienta OAuth (bez niego nie da się połączyć),
 * {@code nextRunAt} = najbliższa automatyczna kopia (puste, gdy wyłączone albo czegoś brakuje).
 */
public record BackupSettingsResponse(boolean googleConfigured, boolean connected, String googleEmail,
        Instant googleConnectedAt, String googleConnectedBy, boolean enabled, int runHour, int keepCount,
        boolean passwordSet, Instant passwordSetAt, String passwordSetBy, boolean running, Instant nextRunAt,
        List<BackupRunResponse> runs) {
}
