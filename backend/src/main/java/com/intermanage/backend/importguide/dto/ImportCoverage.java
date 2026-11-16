package com.intermanage.backend.importguide.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Dni objęte wgranymi raportami sprzedaży i dokumentów (połączone okresy, od najstarszego). Z dziur między nimi
 * makro z Mercalys drukuje zaległe raporty.
 */
public record ImportCoverage(List<DateRange> sales, List<DateRange> documents) {

    public record DateRange(LocalDate from, LocalDate to) {
    }
}
