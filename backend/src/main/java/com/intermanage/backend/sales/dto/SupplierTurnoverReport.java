package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.intermanage.backend.nomenclature.entity.NomenclatureLevel;

/**
 * Zakup netto od dostawcy w okresie po miesiącach i nomenklaturze. {@code months} to pierwsze dni miesięcy okresu,
 * a {@code values} w wierszach idą w tej samej kolejności. Wiersze są płaską listą trzech poziomów (rejon, rodzina,
 * podrodzina, każdy pod swoim rodzicem), na końcu „Bez nomenklatury” ({@code code} pusty, {@code level} pusty).
 */
public record SupplierTurnoverReport(String supplierCode, String supplierName, LocalDate from, LocalDate to,
        LocalDate documentsCoveredUntil, List<LocalDate> months, List<Long> deliveries, List<Amount> totals,
        Amount total, List<Row> rows, UnattachedCorrections unattachedCorrections) {

    /** Zakup z bonów, korekty przypięte do przyjęć i zakup po korektach. */
    public record Amount(BigDecimal delivered, BigDecimal corrections, BigDecimal net) {
    }

    public record Row(String code, NomenclatureLevel level, String name, List<Amount> values, Amount total) {
    }

    /** Korekty dostawcy z okresu, których nikt nie przypiął do przyjęcia: nie są w kwotach raportu. */
    public record UnattachedCorrections(long count, BigDecimal purchaseValue) {
    }
}
