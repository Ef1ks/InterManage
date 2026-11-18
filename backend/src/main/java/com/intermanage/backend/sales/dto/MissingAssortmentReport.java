package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Brakujący asortyment: artykuły kupione od {@code since} od dostawców spoza bazy, których nie ma na stanie i które nie
 * sprzedały się przez {@code silentDays} dni do {@code lastSaleDate} (ostatni dzień z wgraną sprzedażą). Zakładki
 * kategorii, dostawca, wyszukiwanie i sortowanie robi przeglądarka.
 */
public record MissingAssortmentReport(
        String storeNumber,
        String storeName,
        LocalDateTime stockReportGeneratedAt,
        LocalDate lastSaleDate,
        LocalDate since,
        int months,
        int silentDays,
        List<String> baseSupplierCodes,
        List<MissingAssortmentCategoryResponse> categories,
        List<Item> items) {

    /**
     * Dostawca, ilość i cena zakupu netto sztuki z ostatniego przyjęcia PZ spoza bazy w okresie, {@code purchases}: ile
     * było takich przyjęć. {@code lastSale}: ostatnia sprzedaż w ogóle, {@code silentDays}: dni od niej do ostatniego
     * dnia sprzedaży (puste = artykuł nigdy się nie sprzedał).
     *
     * <p>Sprzedaż „gdy był na stanie” liczy się tylko z dni, w których towar był: po każdej dostawie od dnia przyjęcia
     * do ostatniej sprzedaży przed kolejną dostawą ({@code daysInStock}, {@code soldInStock}); dostawa, po której nic się
     * nie sprzedało, się nie liczy, bo nie wiadomo, ile towar leżał. {@code weeklyInStock} = sprzedaż ÷ te dni × 7,
     * {@code weeklyValue} = to razy cena sprzedaży z kartoteki, czyli ile sprzedaży tygodniowo ucieka. Puste, gdy nie
     * ma żadnego takiego okresu. {@code categoryId}: kategoria, do której sklep przypisał artykuł (pusta = do
     * sprawdzenia).
     */
    public record Item(String itm8, String ean, String name, String brand, String subfamilyCode, boolean cadencier,
            BigDecimal stock, BigDecimal salePrice, String supplierCode, String supplierName, int purchases,
            LocalDate lastPurchaseDate, BigDecimal lastPurchaseQuantity, BigDecimal lastPurchasePrice,
            LocalDate lastSale, Long silentDays, BigDecimal soldInStock, Integer daysInStock,
            BigDecimal weeklyInStock, BigDecimal weeklyValue, UUID categoryId) {
    }
}
