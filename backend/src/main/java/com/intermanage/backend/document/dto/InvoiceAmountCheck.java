package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Porównanie przyjęcia PZ z jego fakturami KSeF: suma netto faktur (także faktur korekt od dostawcy przypiętych
 * do przyjęcia) i wartość zakupu netto przyjęcia po korektach, w złotych z groszami. {@code difference} = faktury -
 * przyjęcie; {@code matches}: różnica najwyżej 1 zł w górę lub w dół. {@code confirmedAt}/{@code confirmedBy}: ktoś
 * potwierdził tę różnicę ({@code null}, gdy nie albo kwoty zmieniły się od potwierdzenia).
 */
public record InvoiceAmountCheck(
        BigDecimal invoiceNet,
        BigDecimal documentValue,
        BigDecimal difference,
        boolean matches,
        Instant confirmedAt,
        String confirmedBy) {
}
