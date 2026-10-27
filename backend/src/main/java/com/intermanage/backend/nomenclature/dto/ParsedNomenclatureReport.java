package com.intermanage.backend.nomenclature.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Raport "Lista nomenklatur" po odczytaniu: numer sklepu i data z nagłówka oraz pozycje w kolejności kodów. */
public record ParsedNomenclatureReport(String storeNumber, LocalDateTime generatedAt, List<NomenclatureReportRow> rows) {
}
