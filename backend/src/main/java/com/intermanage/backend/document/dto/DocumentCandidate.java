package com.intermanage.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dokument sklepu (przyjęcie, korekta od dostawcy) do przypięcia faktury KSeF. {@code difference}: o ile wartość
 * zakupu różni się od kwoty netto faktury (bez znaku), {@code sameSupplier}: dostawca dokumentu ma NIP sprzedawcy.
 */
public record DocumentCandidate(
        UUID id,
        String type,
        String typeSingular,
        String number,
        LocalDateTime documentDate,
        String supplierName,
        String supplierDocumentNumber,
        BigDecimal purchaseValue,
        boolean sameSupplier,
        BigDecimal difference) {
}
