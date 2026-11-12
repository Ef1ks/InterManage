package com.intermanage.backend.pricecheck.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Marże artykułów kartoteki z aktualnych cen: raport artykułów z za niską marżą ({@code maxMargin}: marża
 * poniżej tylu procent) albo raport aktualnych
 * promocji (bez gazetki; {@code leafletSkipped}: ile promocji z gazetki pominięto). {@code marginsConfigured}: sklep ma
 * marżę wymaganą. {@code excludedSuppliers}: dostawcy bez marży wymaganej (wspólni z raportem marż poza normą).
 */
public record ArticleMarginReport(
        LocalDate today,
        BigDecimal maxMargin,
        boolean marginsConfigured,
        int leafletSkipped,
        List<ReportSupplier> excludedSuppliers,
        List<Item> items) {

    /** Skąd jest cena zakupu: ostatnie przyjęcie PZ albo kartoteka (bez przyjęcia w ostatnim roku). */
    public enum PurchaseSource {
        DELIVERY, CATALOG
    }

    /** Skąd wiadomo o promocji: lista promocji albo pliki kasy. */
    public enum PromoSource {
        LIST, TILL
    }

    /**
     * Artykuł. {@code salePrice}: aktualna cena zwykła brutto na kasie, {@code marginPercent}: marża z niej (% od
     * sprzedaży netto) przy cenie zakupu {@code purchasePrice}; {@code targetSalePrice}: cena brutto dająca marżę
     * wymaganą przy tej cenie zakupu, {@code targetPurchasePrice}: cena zakupu dająca marżę wymaganą przy cenie zwykłej.
     * {@code promoPrice} / {@code promoMargin}: promocja trwająca dziś i marża na niej. {@code cadencier}: aktywny
     * w cadencier z CENA_CAD ({@code cadencierPrice}), marża ustalona przez centralę.
     */
    public record Item(
            String itm8,
            String ean,
            String name,
            String brand,
            String nomenclatureCode,
            String regionCode,
            String supplierCode,
            String supplierName,
            BigDecimal vatRate,
            BigDecimal purchasePrice,
            PurchaseSource purchaseSource,
            LocalDateTime lastPurchaseDate,
            BigDecimal salePrice,
            BigDecimal marginPercent,
            BigDecimal requiredMargin,
            BigDecimal targetPurchasePrice,
            BigDecimal targetSalePrice,
            BigDecimal promoPrice,
            LocalDate promoFrom,
            LocalDate promoTo,
            PromoSource promoSource,
            BigDecimal promoMargin,
            /** Sprzedana ilość w ostatnich 3 miesiącach (z bieżącym); {@code null} bez sprzedaży. */
            BigDecimal soldQuantity,
            boolean cadencier,
            BigDecimal cadencierPrice,
            /** Obowiązująca weryfikacja (ceny się nie zmieniły od sprawdzenia); {@code null} = do kontroli. */
            Verification verification) {

        public Item withVerification(Verification verification) {
            return new Item(itm8, ean, name, brand, nomenclatureCode, regionCode, supplierCode, supplierName, vatRate,
                    purchasePrice, purchaseSource, lastPurchaseDate, salePrice, marginPercent, requiredMargin,
                    targetPurchasePrice, targetSalePrice, promoPrice, promoFrom, promoTo, promoSource, promoMargin,
                    soldQuantity, cadencier, cadencierPrice, verification);
        }
    }

    /** Artykuł sprawdzony pod kątem za niskiej marży: kto, kiedy, notatka i ceny z chwili sprawdzenia. */
    public record Verification(
            java.util.UUID id,
            java.time.Instant verifiedAt,
            String verifiedBy,
            String note,
            BigDecimal salePrice,
            BigDecimal purchasePrice,
            BigDecimal marginPercent) {
    }
}
