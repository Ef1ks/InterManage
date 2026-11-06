package com.intermanage.backend.bakery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Faktura dostawcy do przypięcia; {@code correctedInvoices}: co koryguje ({@code null} przed odczytem XML). */
public record BakeryInvoiceCandidate(UUID id, String invoiceNumber, LocalDate issueDate, BigDecimal netAmount,
        boolean correction, List<String> correctedInvoices) {
}
