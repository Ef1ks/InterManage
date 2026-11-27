package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lista artykułów po filtrach. {@code items} to najwyżej pierwsze {@code shownLimit} artykułów w kolejności wydruku
 * (podgląd), a {@code itemCount} i {@code groups} liczą całość. {@code groups}: podsumowania grup w kolejności wydruku,
 * od rejonu w dół (przy grupowaniu po rodzinie są rejony i rodziny).
 */
public record ArticleListReport(String storeNumber, String storeName, LocalDateTime stockReportGeneratedAt,
        int itemCount, BigDecimal stockValue, BigDecimal stockSaleValue, int shownLimit, List<Group> groups,
        List<Item> items, Options options) {

    /** {@code level}: 1 rejon, 2 rodzina, 3 podrodzina; {@code code} pusty = bez nomenklatury. */
    public record Group(String code, int level, String name, int count, BigDecimal stockValue,
            BigDecimal stockSaleValue) {
    }

    public record Item(String itm8, String ean, String name, String brand, String catalogSupplier, String regionCode,
            String regionName, String familyCode, String familyName, String subfamilyCode, String subfamilyName,
            BigDecimal stock, BigDecimal rotation, BigDecimal purchasePrice, BigDecimal salePrice, Integer vatRate,
            BigDecimal marginPercent, BigDecimal stockValue, BigDecimal stockSaleValue, String unit,
            BigDecimal netContent, BigDecimal caseSize, boolean active, Short depositCode, String kind, Integer dlv,
            String cnCode, boolean cadencier, BigDecimal cadencierPrice, BigDecimal promoPrice, LocalDate promoFrom,
            LocalDate promoTo, LocalDate lastDeliveryDate, String lastSupplierCode, String lastSupplierName,
            boolean inLastReport) {
    }

    /** Wartości do wyboru w filtrach, z liczbą artykułów kartoteki (bez filtrów). */
    public record Options(List<Option> brands, List<Option> suppliers, List<Option> units, List<Option> kinds,
            List<Option> vatRates) {
    }

    public record Option(String value, String label, int count) {
    }
}
