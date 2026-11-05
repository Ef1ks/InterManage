package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Przepłacone artykuły z ostatnich {@code periodDays} dni (wg daty przyjęcia): przyjęcia PZ z artykułem kupionym
 * o ponad {@code thresholdPercent}% drożej niż w jednej z {@code referenceCount} poprzednich dostaw z normalną ilością
 * oraz przyjęcia, które już mają status wyjaśniania. Dostawa z ilością ponad {@code largeQuantityFactor} razy większą
 * niż zwykle (mediana artykułu) nie bierze udziału w porównaniu.
 */
public record OverpaymentReport(
        LocalDate from,
        LocalDate to,
        int periodDays,
        int thresholdPercent,
        int referenceCount,
        int largeQuantityFactor,
        LocalDate documentsCoveredUntil,
        LocalDateTime documentsReportGeneratedAt,
        List<Delivery> deliveries) {

    /**
     * Przyjęcie do wyjaśnienia. {@code linesPurchaseValue}: zakup przepłaconych artykułów; {@code excessValue}: o ile
     * za nie przepłacono względem najniższych cen odniesienia. {@code review}: obowiązujący status (pusty = do
     * wyjaśnienia), {@code history}: zmiany statusu od najnowszej. Puste {@code lines} = przyjęcie ze statusem, w którym
     * nie ma już przepłaconych artykułów.
     */
    public record Delivery(
            UUID id,
            String number,
            LocalDateTime documentDate,
            String supplierCode,
            String supplierName,
            String supplierDocumentNumber,
            int correctionCount,
            BigDecimal linesPurchaseValue,
            BigDecimal excessValue,
            PriceCheckReviewResponse review,
            List<PriceCheckReviewResponse> history,
            List<Line> lines) {
    }

    /**
     * Przepłacony artykuł. Ilość i cena po korektach; {@code referencePrice}: najniższa z cen odniesienia,
     * {@code overpaidPercent}: o ile cena jest od niej wyższa, {@code excessValue}: różnica × ilość.
     * {@code regionCode}: rejon nomenklatury z linii (do własnych kategorii raportu).
     */
    public record Line(
            String itm8,
            String ean,
            String name,
            String brand,
            String regionCode,
            String supplierCode,
            BigDecimal quantity,
            BigDecimal purchasePrice,
            BigDecimal purchaseValue,
            BigDecimal referencePrice,
            BigDecimal overpaidPercent,
            BigDecimal excessValue,
            boolean corrected,
            List<Reference> references) {
    }

    /** Poprzednia dostawa z ceną z bonu (sprzed korekt), od najnowszej. */
    public record Reference(
            UUID documentId,
            String documentNumber,
            LocalDateTime documentDate,
            String supplierCode,
            BigDecimal quantity,
            BigDecimal purchasePrice) {
    }
}
