package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Faktura weryfikacji; {@code pinned} = false: korekta dołączona sama, po fakturze, którą koryguje. */
public record BakeryInvoice(UUID id, String invoiceNumber, LocalDate issueDate, BigDecimal netAmount,
        boolean correction, boolean pinned) {
}
