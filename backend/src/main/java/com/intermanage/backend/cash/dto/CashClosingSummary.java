package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Zakończony raport dzienny na liście ({@code RK 5/11/2026}); liczby KP i KW to załączniki bez anulowanych.
 * {@code closedBy} puste = zamknięty automatycznie o 23:00.
 */
public record CashClosingSummary(
        UUID id,
        String number,
        LocalDate reportDate,
        BigDecimal openingBalance,
        BigDecimal receipts,
        BigDecimal payments,
        BigDecimal closingBalance,
        int receiptCount,
        int paymentCount,
        String closedBy,
        Instant closedAt) {
}
