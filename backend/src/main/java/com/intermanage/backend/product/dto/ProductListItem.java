package com.intermanage.backend.product.dto;

import java.math.BigDecimal;

/**
 * Artykuł na liście Magazynu i produktów. {@code salePrice}: cena sprzedaży brutto (w promocji regularna),
 * {@code promoPrice}: cena trwającej promocji z kasy, {@code stock} i {@code rotation} stan i rotacja z ostatniego
 * raportu kartoteki.
 */
public record ProductListItem(String itm8, String ean, String name, String brand, String subfamilyCode,
        BigDecimal stock, BigDecimal rotation, BigDecimal salePrice, BigDecimal promoPrice, Boolean active) {
}
