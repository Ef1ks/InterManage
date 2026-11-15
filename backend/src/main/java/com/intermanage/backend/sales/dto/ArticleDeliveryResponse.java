package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dostawa artykułu w oknie wykresu: przyjęcie PZ z ilością i ceną zakupu netto sztuki ({@code purchaseValue} /
 * {@code quantity}) po korektach przypiętych do przyjęcia. Gdy korekty zmieniły ilość albo wartość, {@code original*}
 * to wartości z bonu, inaczej są puste. Dostawca z linii przyjęcia. {@code meaCodes}: kody MEA artykułu z dostawy z
 * bazy tego przyjęcia (Nr_Bl = nr dok. dostawcy) i z formularzy MEA (ITM8 promocyjny w linii przyjęcia), pusta lista,
 * gdy towar nie przyszedł na gazetkę; {@code mea} to te same kody z nazwą i okresem promocji.
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
        List<String> meaCodes,
        List<Mea> mea) {

    /** Gazetka MEA, na którą przyszła dostawa; bez pobranego formularza tylko numer. */
    public record Mea(String number, String title, LocalDate promoFrom, LocalDate promoTo) {
    }
}
