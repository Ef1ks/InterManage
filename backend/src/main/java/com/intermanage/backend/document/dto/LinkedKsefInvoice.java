package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Faktura KSeF dokumentu: przypięta ręcznie albo ({@code matchedByNumber}) dopasowana sama, bo jej numer jest numerem
 * dokumentu dostawcy w przyjęciu. Takiej nie da się odpiąć - zmienia się ją poprawką numeru. {@code viaCorrection}:
 * numer korekty od dostawcy przypiętej do przyjęcia, której to faktura (przyjęcie pokazuje ją obok swojej).
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
        boolean matchedByNumber,
        String viaCorrection) {
}
