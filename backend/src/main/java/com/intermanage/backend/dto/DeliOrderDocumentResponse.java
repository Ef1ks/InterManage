package com.intermanage.backend.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Dokument ruchu międzymagazynowego przypięty do zamówienia garmażu (bez kwot - moduł nie pokazuje cen). */
public record DeliOrderDocumentResponse(
        UUID documentId,
        String number,
        LocalDateTime documentDate,
        int lineCount,
        String linkedBy,
        Instant linkedAt) {
}
