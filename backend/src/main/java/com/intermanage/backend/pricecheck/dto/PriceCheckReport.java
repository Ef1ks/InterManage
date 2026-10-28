package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Weryfikacja zakupu bazowego poza bazą z ostatnich {@code periodDays} dni (wg daty przyjęcia): przyjęcia PZ z artykułami
 * obecnymi w cadencier kupionymi u dostawcy innego niż baza ({@code baseSupplierCodes}) oraz przyjęcia, które już mają
 * status wyjaśniania. {@code minMarginPercent} pusta = bez minimum, wtedy linie nie są oznaczane.
 * {@code cadencierCount}: ile artykułów jest teraz w cadencier (0 = lista nie została wgrana).
 */
public record PriceCheckReport(
        LocalDate from,
        LocalDate to,
        int periodDays,
        BigDecimal minMarginPercent,
        List<String> baseSupplierCodes,
        long cadencierCount,
        LocalDateTime cadencierGeneratedAt,
        Instant cadencierImportedAt,
        LocalDate documentsCoveredUntil,
        LocalDateTime documentsReportGeneratedAt,
        List<Delivery> deliveries) {

    /**
     * Przyjęcie do wyjaśnienia. Kwoty po korektach. {@code linesPurchaseValue}: zakup artykułów z cadencier w tym
     * przyjęciu; {@code belowMinimumCount}: ile z nich ma marżę poniżej minimum; {@code excessValue}: o ile za drogo
     * względem ceny zakupu, przy której marża byłaby minimalna; {@code baseDifferenceValue}: o ile drożej niż ostatnio
     * w bazie (tylko linie z ceną z bazy). {@code review}: obowiązujący status (pusty = do wyjaśnienia),
     * {@code history}: wszystkie zmiany statusu od najnowszej. Puste {@code lines} = po korektach nie ma już artykułów
     * z cadencier od innego dostawcy.
     */
    public record Delivery(
            UUID id,
            String number,
            LocalDateTime documentDate,
            String supplierCode,
            String supplierName,
            String supplierDocumentNumber,
            int correctionCount,
            BigDecimal purchaseValue,
            BigDecimal linesPurchaseValue,
            int belowMinimumCount,
            BigDecimal excessValue,
            BigDecimal baseDifferenceValue,
            PriceCheckReviewResponse review,
            List<PriceCheckReviewResponse> history,
            List<Line> lines) {
    }

    /**
     * Artykuł z cadencier od innego dostawcy ({@code supplierCode} z linii). Ceny za sztukę: zakupu po korektach,
     * sprzedaży z bonu (brutto i netto). {@code marginPercent} pusta, gdy bon nie ma ceny sprzedaży.
     * {@code targetPurchasePrice}: najwyższa cena zakupu, przy której marża byłaby minimalna (pusta bez minimum);
     * {@code excessValue} różna od zera tylko przy {@code belowMinimum}. {@code cadencierPrice}: CENA_CAD z kartoteki;
     * {@code basePrice}: ostatnia cena zakupu z bazy przed tym przyjęciem (do roku wstecz).
     */
    public record Line(
            String itm8,
            String ean,
            String name,
            String brand,
            String supplierCode,
            BigDecimal quantity,
            BigDecimal purchasePrice,
            BigDecimal purchaseValue,
            BigDecimal salePriceGross,
            BigDecimal salePriceNet,
            BigDecimal marginPercent,
            boolean belowMinimum,
            BigDecimal targetPurchasePrice,
            BigDecimal excessValue,
            BigDecimal cadencierPrice,
            BigDecimal basePrice,
            LocalDateTime baseDate,
            UUID baseDocumentId,
            String baseDocumentNumber,
            boolean corrected) {
    }
}
