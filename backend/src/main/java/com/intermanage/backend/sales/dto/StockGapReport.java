package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Braki towaru. {@code lastSaleDate} to ostatni dzień z wgraną sprzedażą, a {@code stockReportGeneratedAt} raport
 * kartoteki, z którego są stany. Zakładki, wyszukiwanie i sortowanie robi przeglądarka, bo pozycji jest najwyżej kilkaset.
 */
public record StockGapReport(
        String storeNumber,
        String storeName,
        LocalDateTime stockReportGeneratedAt,
        LocalDate lastSaleDate,
        BigDecimal minWeekly,
        BigDecimal maxStock,
        int noDeliveryDays,
        List<Item> items) {

    /**
     * {@code typicalWeek}: mediana tygodni ze sprzedażą. {@code silentDays}: dni bez sprzedaży do {@code lastSaleDate}.
     * {@code missedQuantity}: ile by się sprzedało przez ten czas w typowym tempie. {@code coverDays}: na ile dni
     * wystarczy stan, puste przy stanie 0 albo ujemnym. {@code weeklyMargin}: typowy tydzień × marża na sztuce z tych
     * samych tygodni, czyli ile marży tygodniowo jest do stracenia. Ostatnia dostawa to przyjęcie PZ albo towar od
     * innego sklepu, a dostawca z ostatniego PZ.
     */
    public record Item(StockGapKind kind, String itm8, String ean, String name, String brand, String subfamilyCode,
            boolean cadencier, BigDecimal stock, BigDecimal typicalWeek, int weeksSold, LocalDate lastSale,
            long silentDays, BigDecimal missedQuantity, BigDecimal coverDays, BigDecimal weeklyMargin,
            LocalDate lastDeliveryDate, int recentDeliveries, String supplierCode, String supplierName) {
    }
}
