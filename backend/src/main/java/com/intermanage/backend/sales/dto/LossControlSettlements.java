package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Rozliczenia dostawców do kontroli strat w miesiącach okresu raportu: gratisy za pokrycie strat od podpiętego
 * dostawcy z listy dostawców, po miesiącu rozliczenia (wybranym ręcznie przy gratisie albo z daty dokumentu).
 * Grupa bez podpiętego dostawcy nie ma wpisu.
 */
public record LossControlSettlements(YearMonth fromMonth, YearMonth toMonth, List<Group> groups) {

    /** Suma sprzedaży brutto gratisów grupy i same gratisy od najnowszego miesiąca. */
    public record Group(UUID groupId, BigDecimal saleValueGross, List<Gratis> gratis) {
    }

    /** {@code manualMonth}: miesiąc rozliczenia wybrany ręcznie, inny niż miesiąc daty dokumentu. */
    public record Gratis(UUID documentId, String documentType, String documentNumber, LocalDate documentDate,
            YearMonth month, boolean manualMonth, BigDecimal saleValueGross) {
    }
}
