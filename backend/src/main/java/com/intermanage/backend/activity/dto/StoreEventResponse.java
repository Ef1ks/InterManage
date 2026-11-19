package com.intermanage.backend.activity.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.entity.StoreEventLevel;

/** Wpis dziennika sklepu. {@code userName} pusty: zdarzenie bez zalogowanej osoby (zadanie w tle). */
public record StoreEventResponse(UUID id, Instant occurredAt, UUID userId, String userName, StoreEventKind kind,
        StoreEventLevel level, String module, String moduleLabel, String method, String path, Integer status,
        Integer durationMs, String summary, String detail) {
}
