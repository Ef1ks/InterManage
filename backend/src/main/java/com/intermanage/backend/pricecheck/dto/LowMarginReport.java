package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Za niskie marże z ostatnich {@code periodDays} dni (wg daty przyjęcia): przyjęcia PZ z artykułem, którego marża
 * z ceny katalogowej (bez promocji) jest o co najmniej {@code marginGap} punktów procentowych niższa niż marża wymagana
 * na jego nomenklaturze, oraz przyjęcia, które już mają status wyjaśniania. {@code marginsConfigured}: sklep ma
 * ustawioną marżę wymaganą. {@code excludedSuppliers}: dostawcy, których nie dotyczy marża, {@code suppliers}: lista do
 * ich wyboru.
 */
public record LowMarginReport(
        LocalDate from,
        LocalDate to,
        int periodDays,
        int marginGap,
        boolean marginsConfigured,
        LocalDate documentsCoveredUntil,
        LocalDateTime documentsReportGeneratedAt,
        List<ReportSupplier> excludedSuppliers,
        List<ReportSupplier> suppliers,
        List<Delivery> deliveries) {

    /** Skąd wiadomo, że w dniu przyjęcia trwała promocja. */
    public enum PromotionSource {
        LIST, TILL, LEAFLET, SALES
    }

    /**
     * Przyjęcie do wyjaśnienia. {@code linesPurchaseValue}: zakup artykułów z za niską marżą; {@code excessValue}: o ile
     * za drogo je kupiono względem ceny dającej marżę wymaganą. {@code review}: obowiązujący status (pusty = do
     * wyjaśnienia), {@code history}: zmiany statusu od najnowszej. Puste {@code lines} = przyjęcie ze statusem, w którym
     * nie ma już za niskich marż.
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
     * Artykuł z za niską marżą. Ilość i cena zakupu po korektach. {@code salePrice}: cena katalogowa brutto, od niej
     * liczy się marża (% od sprzedaży netto); {@code documentSalePrice}: cena z dokumentu, inna w promocji
     * ({@code promotion}). {@code targetPurchasePrice}: cena zakupu dająca marżę wymaganą, {@code excessValue}: różnica
     * × ilość. {@code regionCode}: rejon nomenklatury z linii (do własnych kategorii raportu).
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
            BigDecimal salePrice,
            BigDecimal documentSalePrice,
            PromotionSource promotion,
            BigDecimal marginPercent,
            BigDecimal requiredMargin,
            BigDecimal targetPurchasePrice,
            BigDecimal excessValue,
            boolean corrected) {
    }
}
