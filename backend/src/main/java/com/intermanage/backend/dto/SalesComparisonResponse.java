package com.intermanage.backend.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Porównanie sprzedaży dwóch okresów na jednym poziomie drzewa nomenklatury, z sumą wszystkich wierszy.
 * {@code parent} to rozwinięta pozycja (pusty tekst = cały sklep, czyli rejony).
 */
public record SalesComparisonResponse(
        LocalDate previousFrom,
        LocalDate previousTo,
        LocalDate currentFrom,
        LocalDate currentTo,
        String parent,
        Level level,
        List<SalesComparisonRow> rows,
        SalesFigures previousTotal,
        SalesFigures currentTotal) {

    /** Czym są wiersze: kolejne poziomy nomenklatury, a pod podrodziną (albo sprzedażą bez nomenklatury) artykuły. */
    public enum Level {
        REGION, FAMILY, SUBFAMILY, PRODUCT
    }
}
