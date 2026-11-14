package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.cash.entity.CashDocumentKind;

/** Zakończony raport dzienny z dokumentami z chwili zamknięcia. */
public record CashClosingResponse(CashClosingSummary summary, List<Row> rows) {

    public record Row(
            UUID documentId,
            int position,
            CashDocumentKind kind,
            String number,
            BigDecimal amount,
            String content,
            String counterparty,
            boolean cancelled,
            BigDecimal balance) {
    }
}
