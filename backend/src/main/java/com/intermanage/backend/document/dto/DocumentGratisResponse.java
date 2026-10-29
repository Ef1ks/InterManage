package com.intermanage.backend.document.dto;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import com.intermanage.backend.document.entity.GratisKind;

/**
 * Rejestracja dokumentu jako gratisu; wartość to sprzedaż brutto dokumentu. {@code reportMonth} to miesiąc wybrany
 * ręcznie, {@code effectiveMonth} miesiąc, do którego gratis liczy się w raporcie.
 */
public record DocumentGratisResponse(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        GratisKind kind,
        YearMonth reportMonth,
        YearMonth effectiveMonth,
        String registeredBy,
        Instant registeredAt) {
}
