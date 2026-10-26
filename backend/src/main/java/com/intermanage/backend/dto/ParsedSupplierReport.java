package com.intermanage.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Raport "Lista dostawców" po odczytaniu: numer sklepu i data z nagłówka oraz wiersze. */
public record ParsedSupplierReport(String storeNumber, LocalDateTime generatedAt, List<SupplierReportRow> rows) {
}
