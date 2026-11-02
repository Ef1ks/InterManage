package com.intermanage.backend.product.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Multikody po odczytaniu: numer sklepu i data z nagłówka, unikalne pary ITM8 + EAN. */
public record ParsedProductCodeReport(String storeNumber, LocalDateTime generatedAt, List<ProductCodeReportRow> rows) {
}
