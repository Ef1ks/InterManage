package com.intermanage.backend.sales.dto;

import java.time.LocalDate;

/** Sprzedaż jednego dnia (dzień bez sprzedaży ma zera). */
public record SalesHistoryDay(LocalDate date, SalesFigures figures) {
}
