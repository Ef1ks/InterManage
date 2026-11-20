package com.intermanage.backend.task.dto;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.task.entity.TaskEventKind;

public record TaskEventResponse(UUID id, TaskEventKind kind, String userName, Instant at, String comment) {
}
