package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ujemne stany. {@code salesFrom}..{@code lastSaleDate} to okres kolumny sprzedaży, {@code today} dzień, od którego
 * liczymy dni bez dostawy. Sortowanie i wyszukiwanie robi przeglądarka: artykułów z ujemnym stanem jest 2-3 tys.
 */
public record NegativeStockReport(
        String storeNumber,
        String storeName,
        LocalDateTime stockReportGeneratedAt,
        LocalDate lastSaleDate,
        LocalDate salesFrom,
        LocalDate today,
        NegativeStockDelivery delivery,
        int days,
        LocalDate from,
        LocalDate to,
        int pairTolerance,
        List<Item> items) {

    /**
     * {@code stockValue}: stan × cena zakupu z kartoteki (ujemna). {@code soldQuantity}, {@code soldGross}: sprzedaż z
     * okresu raportu, {@code lastSale}: ostatnia sprzedaż w ogóle. Dostawa to przyjęcie PZ albo towar od innego sklepu
     * (MM na minus), także pod lokalnym ITM8 dostawcy po EAN-ie; {@code periodDeliveries} to dni z dostawą w okresie
     * od-do (tylko przy {@link NegativeStockDelivery#DELIVERED_IN}), a dostawca z ostatniego PZ. {@code counterparts}:
     * podobne artykuły na plusie ze stanem zbliżonym do brakującej ilości, najlepiej pasujący pierwszy.
     */
    public record Item(String itm8, String ean, String name, String brand, String subfamilyCode, boolean cadencier,
            boolean active, BigDecimal stock, BigDecimal stockValue, BigDecimal soldQuantity, BigDecimal soldGross,
            LocalDate lastSale, LocalDate lastDeliveryDate, Long daysSinceDelivery, int deliveryDays,
            int periodDeliveries, String supplierCode, String supplierName, List<Counterpart> counterparts) {
    }

    /**
     * Podobny artykuł na plusie: {@code similarity} nazw 0-1 (pg_trgm), {@code soldQuantity} sprzedaż z okresu raportu.
     */
    public record Counterpart(String itm8, String ean, String name, String brand, String subfamilyCode,
            BigDecimal stock, BigDecimal stockValue, double similarity, boolean sameSubfamily, boolean sameBrand,
            BigDecimal soldQuantity) {
    }
}
