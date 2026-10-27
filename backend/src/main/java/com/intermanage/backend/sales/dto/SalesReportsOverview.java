package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Kafelki raportów sprzedaży: za jakie dni jest sprzedaż i kiedy wygenerowano najnowszy wgrany raport. */
public record SalesReportsOverview(LocalDate firstSaleDate, LocalDate lastSaleDate,
        LocalDateTime lastReportGeneratedAt) {
}
