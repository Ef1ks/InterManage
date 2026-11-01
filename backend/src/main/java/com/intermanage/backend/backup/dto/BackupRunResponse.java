package com.intermanage.backend.backup.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.backup.entity.BackupStatus;

/** Próba kopii w historii. {@code startedBy} puste = automatyczna. */
public record BackupRunResponse(UUID id, boolean manual, BackupStatus status, Instant startedAt, String startedBy,
        Instant finishedAt, String fileName, Long sizeBytes, String googleEmail, String error, Instant trashedAt) {
}
