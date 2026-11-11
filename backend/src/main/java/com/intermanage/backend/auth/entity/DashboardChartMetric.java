package com.intermanage.backend.auth.entity;

/** Co pokazuje wykres wyników sklepu na pulpicie. Nazwa stałej trafia do bazy. */
public enum DashboardChartMetric {
    /** Sprzedana ilość. */
    QUANTITY,
    /** Sprzedaż brutto po rabacie. */
    SALES,
    /** Marża w złotych (sprzedaż netto minus koszt FIFO). */
    MARGIN
}
