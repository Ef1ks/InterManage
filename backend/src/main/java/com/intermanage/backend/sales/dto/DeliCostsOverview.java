package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Skąd raport kosztów garmażu bierze koszty: protokoły reprezentacji z raportu Wejścia/Wyjścia. Kiedy wygenerowano
 * najnowszy wgrany raport dokumentów i do którego dnia sięgają wgrane raporty.
 */
public record DeliCostsOverview(LocalDateTime documentsReportGeneratedAt, LocalDate costsCoveredUntil) {
}
