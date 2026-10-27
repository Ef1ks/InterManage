package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Nagłówek raportu sprzedaży: "PDV: 10023 - ... Data: 26/10/2026 Godzina: 15:03:27", "Od 31/12/2025 do 30/06/2026". */
public record SalesReportHeader(String storeNumber, LocalDateTime generatedAt, LocalDate periodFrom, LocalDate periodTo) {
}
