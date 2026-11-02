package com.intermanage.backend.basedelivery.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.dataentry.dto.CatalogProduct;

/**
 * Linia dokumentu dostawy z bazy z artykułem z kartoteki sklepu. {@code itm8} to kod z pliku (8 cyfr); artykuł
 * szukamy po nim, a bez trafienia po EAN-ie, bo baza wysyła część towaru pod własnym ITM8. {@code product} = null to
 * nowy artykuł. {@code quantity} = przygotowane kartony × {@code pack} (sztuki przyjęte), {@code shortDated}: termin
 * przed {@code requiredBestBefore} (dzień dostawy + dni dla sklepu). {@code catalogDeposit} to kod kaucji
 * z kartoteki (60 szkło, 70 puszka, 80 PET, 1 butelka zwrotna, 0 bez kaucji).
 */
public record BaseDeliveryLineResponse(
        UUID id,
        int lineNo,
        String carrier,
        String orderNumber,
        String itm8,
        String ean,
        String name,
        String brand,
        BigDecimal pack,
        String deposit,
        LocalDate bestBefore,
        Integer daysForStore,
        LocalDate requiredBestBefore,
        boolean shortDated,
        String supplierCode,
        String meaCode,
        BigDecimal coliOrdered,
        BigDecimal coliPrepared,
        BigDecimal quantity,
        BigDecimal purchasePrice,
        BigDecimal retailPrice,
        BigDecimal vatRate,
        BigDecimal netValue,
        String regionCode,
        String familyCode,
        String subfamilyCode,
        BigDecimal saleContent,
        String saleUnit,
        String cn,
        CatalogProduct product,
        Short catalogDeposit) {
}
