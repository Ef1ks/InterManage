package com.intermanage.backend.document.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record LinkDocumentRequest(@NotNull(message = "Wybierz dokument") UUID documentId) {
}
