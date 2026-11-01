package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dostawa artykułu w oknie wykresu: przyjęcie PZ z ilością i ceną zakupu netto sztuki ({@code purchaseValue} /
 * {@code quantity}, z raportu, bez korekt). Dostawca z linii przyjęcia.
 */
public record ArticleDeliveryResponse(
        UUID documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String supplierCode,
        String supplierName,
        BigDecimal quantity,
        BigDecimal purchasePrice,
        BigDecimal purchaseValue) {
}
