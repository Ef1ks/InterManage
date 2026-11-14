package com.intermanage.backend.cash.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Raport kasowy za okres: stan na początek, dokumenty z okresu (także anulowane, bez wpływu na stan), obroty i stan
 * na koniec. {@code balance} wiersza to stan kasy po dokumencie, przy anulowanym {@code null}.
 */
public record CashReport(
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        BigDecimal receipts,
        BigDecimal payments,
        BigDecimal closingBalance,
        List<Row> rows,
        CashSettings settings) {

    public record Row(CashDocumentResponse document, BigDecimal balance) {
    }
}
