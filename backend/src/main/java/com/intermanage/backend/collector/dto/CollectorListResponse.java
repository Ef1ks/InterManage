package com.intermanage.backend.collector.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.collector.entity.CollectorListOutput;

/** Lista z kolektora; {@code protocols} puste = czeka na wybór protokołu. */
public record CollectorListResponse(UUID id, String collectorName, LocalDateTime startedAt, Instant receivedAt,
        int lineCount, List<Protocol> protocols) {

    /** Protokół zapisany z tej listy. */
    public record Protocol(UUID id, CollectorListOutput kind, String fileName, Instant createdAt, String createdBy) {
    }
}
