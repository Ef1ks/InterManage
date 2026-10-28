package com.intermanage.backend.sales.dto;

import java.time.LocalDate;
import java.util.List;

/** Sprzedaż artykułu albo pozycji nomenklatury dzień po dniu, każdy dzień okresu po kolei, z sumą okresu. */
public record SalesHistoryResponse(
        LocalDate from,
        LocalDate to,
        List<SalesHistoryDay> days,
        SalesFigures total) {
}
