package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.document.dto.LinkedKsefInvoice;

/**
 * Weryfikacja przyjęć z ostatnich {@code periodDays} dni (wg daty przyjęcia): przyjęcia PZ z anomalią w którymś
 * artykule, każde z całym bonem po korektach, oraz przyjęcia, które już mają status wyjaśniania. Anomalie
 * ({@link Anomaly}) liczą się tą samą logiką co osobne raporty: marża z aktualnej ceny na kasie o co najmniej
 * {@code lowGap} punktów procentowych niższa albo o co najmniej {@code highGap} wyższa niż wymagana, przepłacenie
 * wobec poprzednich dostaw i artykuł z cadencier kupiony poza bazą. {@code marginsConfigured}: sklep ma ustawioną
 * marżę wymaganą. {@code excludedSuppliers}: dostawcy pomijani w raporcie, {@code suppliers}: lista do ich wyboru.
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

    /**
     * Rodzaj anomalii artykułu: za niska albo za wysoka marża wobec wymaganej, zakup drożej niż w poprzednich dostawach
     * (raport przepłaconych) i artykuł z cadencier od dostawcy spoza bazy (raport zakupu poza bazą, bez artykułów
     * usprawiedliwionych incydentem zamówienia do bazy).
     */
    public enum Anomaly {
        LOW_MARGIN, HIGH_MARGIN, OVERPAID, OUTSIDE_BASE
    }

    /** Skąd wiadomo, że w dniu przyjęcia trwała promocja. */
    public enum PromotionSource {
        LIST, TILL, LEAFLET, SALES
    }

    /**
     * Przyjęcie do wyjaśnienia: {@code lines} to artykuły z anomalią, od największej możliwej straty (cały bon
     * pobiera się osobno, {@code lineCount} artykułów). {@code linesPurchaseValue}: zakup całego bonu po korektach; {@code excessValue}: możliwa strata na
     * artykułach z anomalią ({@link Line#lossValue()}). {@code anomalyCount}: ile artykułów ma anomalię, a liczby
     * {@code lowMarginCount}…{@code outsideBaseCount}: ile każdego rodzaju (artykuł może mieć kilka).
     * {@code review}: obowiązujący status (pusty = do wyjaśnienia), {@code history}: zmiany statusu od najnowszej.
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
            /** Faktury KSeF przyjęcia i jego korekt od dostawcy (do wydruku z raportu). */
            List<LinkedKsefInvoice> ksefInvoices,
            /** Ile artykułów ma cały bon (pobiera się go osobno po rozwinięciu przyjęcia). */
            int lineCount,
            int anomalyCount,
            int lowMarginCount,
            int highMarginCount,
            int overpaidCount,
            int outsideBaseCount) {
    }

    /**
     * Artykuł przyjęcia. Ilość i cena zakupu po korektach; bez ceny sprzedaży albo stawki VAT pola marży są puste. {@code salePrice}: aktualna cena zwykła brutto na kasie
     * (bez artykułu w kartotece cena katalogowa z dnia przyjęcia), od niej liczy się marża (% od sprzedaży brutto);
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
            /** Marża (% od sprzedaży brutto) na cenie promocyjnej {@code currentPromoPrice}; {@code null} bez promocji. */
            BigDecimal currentPromoMargin,
            /** Anomalie artykułu; puste = w normie. */
            List<Anomaly> anomalies,
            /** Przepłacenie wobec poprzednich dostaw (jak w raporcie przepłaconych); {@code null} bez niego. */
            OverpaymentReport.Line overpaid,
            /** Zakup poza bazą (jak w raporcie zakupu poza bazą); {@code null}, gdy artykuł nie jest z cadencier. */
            PriceCheckReport.Line outsideBase,
            /**
             * Możliwa strata: największa z brakującej marży, nadpłaty wobec poprzednich dostaw, kwoty ponad cenę
             * dającą minimalną marżę i różnicy wobec ostatniej ceny z bazy; zero bez anomalii.
             */
            BigDecimal lossValue) {

        /** Ta sama linia z anomaliami i szczegółami z pozostałych raportów. */
        public Line withAnomalies(MarginDeviation deviation, List<Anomaly> anomalies, OverpaymentReport.Line overpaid,
                PriceCheckReport.Line outsideBase, BigDecimal lossValue) {
            return new Line(itm8, ean, name, brand, regionCode, supplierCode, quantity, purchasePrice, purchaseValue,
                    salePrice, documentSalePrice, promotion, marginPercent, requiredMargin, targetPurchasePrice,
                    excessValue, corrected, deviation, currentPromoPrice, currentPromoTo, currentPromoMargin,
                    anomalies, overpaid, outsideBase, lossValue);
        }
    }
}
