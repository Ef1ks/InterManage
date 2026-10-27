package com.intermanage.backend.deliorder.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Dokument ruchu międzymagazynowego dostawcy do przypięcia; {@code linked}, gdy już jest przy tym zamówieniu. */
public record DeliDocumentCandidate(
        UUID documentId,
        String number,
        LocalDateTime documentDate,
        int lineCount,
        boolean linked) {
}
