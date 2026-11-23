package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sprzedaż dzień po dniu po nomenklaturze: {@code days} to każdy dzień okresu, a {@link Line#days()} ma na każdy
 * dzień jedną pozycję w tej samej kolejności ({@code null} = brak sprzedaży tego dnia). Rejony mają rodziny w
 * {@code children}; kod pusty = bez nomenklatury. {@code lastSaleDate}: ostatni dzień okresu ze sprzedażą.
 */
public record DailySalesReport(LocalDate from, LocalDate to, List<LocalDate> days, LocalDate lastSaleDate,
        Line total, List<Line> regions) {

    /** Sumy pozycji w dniu albo w okresie; marża od sprzedaży brutto liczy się z sum. */
    public record Figures(BigDecimal quantity, BigDecimal saleValueGross, BigDecimal saleValueNet,
            BigDecimal marginValue) {
    }

    /** {@code level}: CATEGORY (kod = id kategorii albo {@code pozostale}), REGION, FAMILY albo TOTAL. */
    public record Line(String code, String name, String level, List<Figures> days, Figures sum,
            List<Line> children) {
    }
}
