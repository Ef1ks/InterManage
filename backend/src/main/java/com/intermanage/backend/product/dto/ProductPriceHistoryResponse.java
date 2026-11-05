package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Historia cen artykułu: promocje z listy promocji (przeceny) i zmiany ceny sprzedaży od najnowszej; {@code mainEan}
 * to główny EAN z kartoteki.
 */
public record ProductPriceHistoryResponse(String mainEan, List<Promotion> promotions, List<PriceChange> changes) {

    /** Promocja z listy promocji; {@code cancelled}: zabrakło jej w nowszym raporcie (odwołana). */
    public record Promotion(LocalDate startDate, LocalDate endDate, BigDecimal regularSalePrice,
            BigDecimal promoPrice, BigDecimal catalogPurchasePrice, BigDecimal marginPercent, boolean cancelled) {
    }

    /**
     * Zmiana ceny. {@code source}: TILL plik z kasy (TRACEART), osobno dla każdego {@code ean}, bez ceny poprzedniej
     * przy pierwszej znanej cenie EAN-u; REPORT import kartoteki i DATA_ENTRY plik zmiany cen z wprowadzania danych
     * dotyczą całego artykułu, więc są bez {@code ean} (DATA_ENTRY bez ceny poprzedniej, z dostawcą i fakturą
     * w {@code description} i autorem).
     */
    public record PriceChange(String ean, LocalDateTime changedAt, String source, BigDecimal previousSalePrice,
            BigDecimal salePrice, BigDecimal previousPromoPrice, BigDecimal promoPrice, LocalDate promoFrom,
            LocalDate promoTo, String description, String user) {
    }
}
