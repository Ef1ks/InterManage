package com.intermanage.backend.task.dto;

import java.util.UUID;

/** Konto, któremu można zlecić zadanie w sklepie. */
public record TaskAssigneeResponse(UUID id, String displayName, String roleName) {
}
