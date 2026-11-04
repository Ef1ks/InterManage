package com.intermanage.backend.leaflet.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.intermanage.backend.dataentry.dto.CatalogProduct;

/**
 * Artykuł dla EAN-u z gazetki po zmianie przypięcia ({@code ean} bez zer wiodących): przypięty ręcznie
 * ({@code linkId}) albo, bez przypięcia, znaleziony po EAN-ie; {@code product} {@code null} = brak.
 */
public record LeafletMatch(String ean, UUID linkId, CatalogProduct product, BigDecimal regularPrice) {
}
