package com.intermanage.backend.basedelivery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linia pliku dostawy z bazy po odczycie ({@code BaseDeliveryParser}); opis kolumn w V71__base_deliveries.sql.
 * Puste kody ({@code ----------}, same zera w EAN-ie, {@code 0000-00-00}) są tu już {@code null}.
 */
public record BaseDeliveryRow(
        String carrier,
        String orderNumber,
        String itm8,
        String name,
        String brand,
        BigDecimal pack,
        String deposit,
        LocalDate bestBefore,
        Integer daysForStore,
        String ean,
        String pkwiu,
        String supplierCode,
        String meaCode,
        BigDecimal coliOrdered,
        BigDecimal coliPrepared,
        BigDecimal purchasePrice,
        BigDecimal retailPrice,
        BigDecimal vatRate,
        BigDecimal vatValue,
        BigDecimal netValue,
        BigDecimal grossValue,
        String regionCode,
        String familyCode,
        String subfamilyCode,
        BigDecimal saleContent,
        String saleUnit,
        String cn,
        String gtu) {
}
