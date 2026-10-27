package com.intermanage.backend.document.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dokument do przypięcia: korekta do przyjęcia albo przyjęcie do korekty. {@code commonItems}: ile artykułów (ITM8)
 * korekty jest też w przyjęciu - po tym najłatwiej poznać, której dostawy dotyczy korekta.
 */
public record CorrectionCandidate(
        UUID id,
        String type,
        String typeSingular,
        String number,
        LocalDateTime documentDate,
        String supplierName,
        String supplierDocumentNumber,
        int lineCount,
        BigDecimal purchaseValue,
        int commonItems) {
}
