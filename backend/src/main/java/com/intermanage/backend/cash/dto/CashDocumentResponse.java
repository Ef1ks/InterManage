package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.cash.entity.CashDocumentKind;

/** Dokument kasowy; {@code content} = operacja z opisem (treść w raporcie). */
public record CashDocumentResponse(
        UUID id,
        CashDocumentKind kind,
        String number,
        LocalDate issueDate,
        BigDecimal amount,
        String operation,
        String title,
        String content,
        String counterparty,
        String counterpartyAddress,
        String createdBy,
        Instant createdAt,
        String cancelledBy,
        Instant cancelledAt,
        String cancelReason) {
}
