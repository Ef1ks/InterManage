package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Artykuł z kartoteki w oknie „Lista towarów”. {@code purchasePrice} to cena zakupu netto sztuki (przy wagowych 1 kg),
 * {@code salePrice} cena sprzedaży brutto, {@code stock} i {@code rotation} stan i rotacja z ostatniego raportu kartoteki, {@code supplier} dostawca
 * z kartoteki, {@code subfamilyCode} i {@code subfamilyName} podrodzina nomenklatury.
 */
public record CatalogListItem(String itm8, String ean, String name, String brand, String supplier,
        String subfamilyCode, String subfamilyName, BigDecimal purchasePrice, BigDecimal salePrice,
        BigDecimal stock, BigDecimal rotation) {
}
