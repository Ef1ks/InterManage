package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sprzedaż jednego dnia (dzień bez sprzedaży ma zera) i straty: likwidacje z tego dnia w wartości zakupu netto
 * (dodatnie, 0 = bez likwidacji).
 */
public record SalesHistoryDay(LocalDate date, SalesFigures figures, BigDecimal lossValue) {
}
