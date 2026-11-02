package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Linia pliku TRACEART (jeden kod EAN artykułu). {@code salePrice} to cena regularna brutto; w promocji
 * {@code promoPrice} to cena na kasie w okresie {@code promoFrom}-{@code promoTo}, poza nią wszystkie trzy są puste.
 */
public record PriceFileLine(String itm8, String ean, BigDecimal salePrice, BigDecimal promoPrice, LocalDate promoFrom,
        LocalDate promoTo) {
}
