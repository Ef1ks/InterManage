package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.util.List;

/** Karta sprzedaży w Imporcie danych: ile wierszy jest w bazie, za jakie dni i ostatnie wgrania. */
public record SalesImportOverview(long rowCount, LocalDate firstSaleDate, LocalDate lastSaleDate,
        List<SalesImportResponse> imports) {
}
