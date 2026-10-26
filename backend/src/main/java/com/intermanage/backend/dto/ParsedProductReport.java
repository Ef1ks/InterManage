package com.intermanage.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Odczytany raport kartoteki. {@code storeNumber} i {@code generatedAt} pochodzą z nagłówka raportu
 * i są {@code null}, gdy plik ich nie zawiera.
 */
public record ParsedProductReport(String storeNumber, LocalDateTime generatedAt, List<ProductReportRow> rows) {
}
