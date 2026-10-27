package com.intermanage.backend.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Przypięcie dokumentu ruchu międzymagazynowego do zamówienia garmażu. */
public record LinkDeliDocumentRequest(@NotNull(message = "Wybierz dokument") UUID documentId) {
}
