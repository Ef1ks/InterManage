package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dostawa artykułu w oknie wykresu: przyjęcie PZ z ilością i ceną zakupu netto sztuki ({@code purchaseValue} /
 * {@code quantity}) po korektach przypiętych do przyjęcia. Gdy korekty zmieniły ilość albo wartość, {@code original*}
 * to wartości z bonu, inaczej są puste. Dostawca z linii przyjęcia. {@code meaCodes}: kody MEA artykułu z dostawy z
 * bazy tego przyjęcia (Nr_Bl = nr dok. dostawcy), pusta lista bez dostawy z bazy albo bez kodu.
 */
public record ArticleDeliveryResponse(
        UUID documentId,
        String documentNumber,
        LocalDateTime documentDate,
        String supplierCode,
        String supplierName,
        BigDecimal quantity,
        BigDecimal purchasePrice,
        BigDecimal purchaseValue,
        boolean corrected,
        BigDecimal originalQuantity,
        BigDecimal originalPurchasePrice,
        BigDecimal originalPurchaseValue,
        List<String> meaCodes) {
}
