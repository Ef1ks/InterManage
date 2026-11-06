package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.nomenclature.dto.NomenclatureOption;

/**
 * Niesprzedające się artykuły: na stanie, z rotacją tygodniową nie wyższą niż {@code maxRotation} w okresie.
 * {@code stockReportGeneratedAt} to data raportu kartoteki, z którego są stany i ceny zakupu. Sumy i {@code items}
 * dotyczą wybranej kategorii; {@code categoryCounts} i {@code otherCount} to liczby przed jej wyborem (do zakładek),
 * a {@code suppliers} lista dostawców przed wyborem dostawcy. {@code deliveries} jest puste bez wyboru okresu dostaw.
 */
public record SlowMovingReport(
        String storeNumber,
        String storeName,
        LocalDateTime stockReportGeneratedAt,
        LocalDate from,
        LocalDate to,
        BigDecimal maxRotation,
        int itemCount,
        int unsoldCount,
        BigDecimal stockValue,
        int allCount,
        int otherCount,
        List<CategoryCount> categoryCounts,
        List<SupplierCount> suppliers,
        List<NomenclatureOption> selection,
        List<Item> items,
        List<Delivery> deliveries) {

    public record CategoryCount(UUID id, int count) {
    }

    public record SupplierCount(String code, String name, int count) {
    }

    /** {@code stockValue}: stan × cena zakupu z kartoteki. Dostawca i data z ostatniego przyjęcia PZ. */
    public record Item(String itm8, String ean, String name, String brand, String subfamilyCode, boolean cadencier,
            BigDecimal stock, BigDecimal stockValue, BigDecimal soldQuantity, BigDecimal weeklyRotation,
            LocalDate lastDeliveryDate, String supplierCode, String supplierName) {
    }

    /** Przyjęcie PZ z artykułami z raportu; {@code purchaseValue} to wartość tych artykułów, nie całego przyjęcia. */
    public record Delivery(UUID documentId, String documentNumber, LocalDateTime documentDate, String supplierCode,
            String supplierName, BigDecimal purchaseValue, List<DeliveryItem> items) {
    }

    public record DeliveryItem(String itm8, BigDecimal quantity, BigDecimal purchaseValue) {
    }
}
