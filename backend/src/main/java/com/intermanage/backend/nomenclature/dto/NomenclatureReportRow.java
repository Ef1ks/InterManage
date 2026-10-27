package com.intermanage.backend.nomenclature.dto;

/**
 * Wiersz raportu "Lista nomenklatur": kod bez spacji ("01", "0101", "010101") i dane z kolumn. Puste kolumny
 * (np. St.Mag./Reappro) dają {@code null}.
 */
public record NomenclatureReportRow(
        String code,
        String name,
        Short vatCode,
        String unit,
        Short labelCount,
        Short labelFormat,
        String tillMode,
        String saleType,
        String stockMode) {
}
