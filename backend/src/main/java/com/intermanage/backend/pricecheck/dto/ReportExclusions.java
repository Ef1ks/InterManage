package com.intermanage.backend.pricecheck.dto;

import java.util.List;

/**
 * Listy pomijanych w raporcie weryfikującym zakupy: kody nomenklatur ({@code nomenclatures}, 2, 4 albo 6 cyfr)
 * i dostawcy ({@code suppliers}); {@code supplierOptions}: dostawcy sklepu do wyboru.
 */
public record ReportExclusions(
        List<String> nomenclatures,
        List<ReportSupplier> suppliers,
        List<ReportSupplier> supplierOptions) {
}
