package com.intermanage.backend.agent.dto;

import java.time.Instant;
import java.util.UUID;

/** Próba wgrania raportu przez agenta sklepu (Import danych pokazuje ostatnie). */
public record AgentReportFileResponse(UUID id, String kind, String fileName, Instant fileModified, long fileSize,
        String status, String error, Instant startedAt, Instant finishedAt, Instant removedFromDiskAt) {
}
