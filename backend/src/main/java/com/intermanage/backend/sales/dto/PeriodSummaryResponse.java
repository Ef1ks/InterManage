package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Podsumowanie sprzedaży i strat za okres: wiersze w kolejności wydruku. Grupy (nagłówek, rejony, suma grupy)
 * jak w zestawieniu sklepu: artykuły spożywcze (świeże tradycyjne, świeże samoobsługa, suche), przemysłowe,
 * papierosy, pozostałe. Do tego dane o tym, do kiedy wgrano sprzedaż i dokumenty (ostrzeżenia o niepełnym okresie).
 */
public record PeriodSummaryResponse(
        String storeNumber,
        String storeName,
        LocalDate from,
        LocalDate to,
        /** Ostatni dzień zapisanej sprzedaży; {@code null} = sklep nie ma sprzedaży. */
        LocalDate lastSaleDate,
        /** Do którego dnia sięgają wgrane raporty dokumentów (likwidacje). */
        LocalDate documentsCoveredUntil,
        LocalDateTime salesReportGeneratedAt,
        LocalDateTime documentsReportGeneratedAt,
        /** Czy sklep ma ustawiony budżet choć jednego rejonu. */
        boolean budgetsSet,
        List<Line> lines,
        Figures total) {

    public enum Kind {
        /** Nagłówek grupy, bez liczb. */
        GROUP,
        REGION,
        /** Suma grupy. */
        SUBTOTAL
    }

    /**
     * Wiersz raportu. {@code depth} to poziom grupy (0 = najwyższy). Rejon ma kod (pusty = bez nomenklatury) i budżet
     * ({@code null} = nieustawiony); nagłówek grupy nie ma liczb.
     */
    public record Line(
            Kind kind,
            int depth,
            String code,
            String name,
            Figures figures,
            BigDecimal marginBudgetPercent,
            BigDecimal maxLossPercent) {
    }

    /**
     * Liczby wiersza. Marża i marża po stratach w % od sprzedaży netto, straty w % od sprzedaży brutto (jak budżet
     * strat), udział = sprzedaż brutto wiersza w sprzedaży brutto sklepu. Procenty puste, gdy nie ma od czego liczyć.
     */
    public record Figures(
            BigDecimal saleValueNet,
            BigDecimal saleValueGross,
            BigDecimal costValue,
            BigDecimal marginValue,
            BigDecimal marginPercent,
            BigDecimal lossValue,
            BigDecimal lossPercent,
            BigDecimal marginAfterLossesValue,
            BigDecimal marginAfterLossesPercent,
            BigDecimal sharePercent) {
    }
}
