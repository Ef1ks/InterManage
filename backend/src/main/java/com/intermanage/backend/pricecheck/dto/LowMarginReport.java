package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.document.dto.LinkedKsefInvoice;

/**
 * Marże poza normą z ostatnich {@code periodDays} dni (wg daty przyjęcia): przyjęcia PZ z artykułem, którego marża
 * z aktualnej ceny na kasie jest o co najmniej {@code lowGap} punktów procentowych niższa albo o co najmniej
 * {@code highGap} wyższa niż marża wymagana na jego nomenklaturze, oraz przyjęcia, które już mają status wyjaśniania. {@code marginsConfigured}: sklep ma
 * ustawioną marżę wymaganą. {@code excludedSuppliers}: dostawcy, których nie dotyczy marża, {@code suppliers}: lista do
 * ich wyboru.
 */
public record LowMarginReport(
        LocalDate from,
        LocalDate to,
        int periodDays,
        BigDecimal lowGap,
        BigDecimal highGap,
        boolean marginsConfigured,
        LocalDate documentsCoveredUntil,
        LocalDateTime documentsReportGeneratedAt,
        List<ReportSupplier> excludedSuppliers,
        List<ReportSupplier> suppliers,
        List<Delivery> deliveries) {

    /** Marża artykułu: za niska ({@code LOW}) albo za wysoka ({@code HIGH}) wobec wymaganej. */
    public enum MarginDeviation {
        LOW, HIGH
    }

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
            List<Line> lines,
            /** Weryfikacja przyjęcia (ta sama co w Dokumentach): status, kiedy i kto; {@code null} = bez statusu. */
            String verificationStatus,
            Instant verificationAt,
            String verificationBy,
            /** Faktury KSeF przyjęcia i jego korekt od dostawcy (do wydruku z raportu). */
            List<LinkedKsefInvoice> ksefInvoices) {
    }

    /**
     * Artykuł z za niską marżą. Ilość i cena zakupu po korektach. {@code salePrice}: aktualna cena zwykła brutto na kasie
     * (bez artykułu w kartotece cena katalogowa z dnia przyjęcia), od niej liczy się marża (% od sprzedaży netto);
     * {@code documentSalePrice}: cena z dokumentu PZ, {@code promotion}: była promocyjna. {@code targetPurchasePrice}: cena zakupu dająca marżę wymaganą, {@code excessValue}: różnica
     * × ilość (przy za niskiej marży brakująca marża, przy za wysokiej nadwyżka, obie na plus). {@code deviation}: za
     * niska albo za wysoka. {@code currentPromoPrice} / {@code currentPromoTo}: artykuł jest dziś w promocji (cena
     * promocyjna i koniec); marża liczy się dalej z ceny zwykłej. {@code regionCode}: rejon nomenklatury z linii (do własnych kategorii raportu).
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
            boolean corrected,
            MarginDeviation deviation,
            BigDecimal currentPromoPrice,
            LocalDate currentPromoTo,
            /** Marża (% od sprzedaży netto) na cenie promocyjnej {@code currentPromoPrice}; {@code null} bez promocji. */
            BigDecimal currentPromoMargin) {
    }
}
