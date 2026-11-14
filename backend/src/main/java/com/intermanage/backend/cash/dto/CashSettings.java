package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.cash.entity.CashDocumentKind;

/**
 * Pieczątka i stan początkowy kasy sklepu. Bez pieczątki nie da się wystawić dokumentu; bez stanu początkowego raport
 * liczy od zera. {@code lastClosedDate}: ostatni zakończony raport dzienny; dokumenty mogą mieć datę dopiero po nim.
 * {@code operations}: operacje „Za co” użyte już w sklepie (KP i KW osobno, od najczęstszej), do listy w formularzu.
 */
public record CashSettings(Stamp stamp, OpeningBalance openingBalance, LocalDate lastClosedDate,
        Map<CashDocumentKind, List<String>> operations) {

    public record Stamp(UUID id, String fileName, String createdBy, Instant createdAt) {
    }

    public record OpeningBalance(LocalDate date, BigDecimal amount, String createdBy, Instant createdAt) {
    }
}
