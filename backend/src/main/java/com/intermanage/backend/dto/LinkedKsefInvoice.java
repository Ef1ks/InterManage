package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Faktura KSeF dokumentu: przypięta ręcznie albo ({@code matchedByNumber}) dopasowana sama, bo jej numer jest numerem
 * dokumentu dostawcy w przyjęciu. Takiej nie da się odpiąć - zmienia się ją poprawką numeru.
 */
public record LinkedKsefInvoice(
        UUID id,
        String ksefNumber,
        String invoiceNumber,
        LocalDate issueDate,
        String sellerName,
        BigDecimal netAmount,
        BigDecimal grossAmount,
        boolean correction,
        boolean matchedByNumber) {
}
