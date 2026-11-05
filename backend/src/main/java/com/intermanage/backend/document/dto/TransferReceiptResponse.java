package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Dokument sklepu docelowego potwierdzający wprowadzenie ruchu międzymagazynowego. */
public record TransferReceiptResponse(
        UUID documentId,
        String number,
        LocalDateTime documentDate,
        BigDecimal purchaseValue,
        String linkedBy,
        Instant linkedAt) {
}
