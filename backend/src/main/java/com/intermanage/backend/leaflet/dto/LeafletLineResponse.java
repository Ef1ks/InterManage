package com.intermanage.backend.leaflet.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.dataentry.dto.CatalogProduct;

/**
 * Pozycja gazetki z artykułem z kartoteki ({@code product}; {@code null} = nie znaleziono). {@code linkId}: artykuł
 * wybrany ręcznie dla tego EAN-u z gazetki (zapamiętany w sklepie), bez niego znaleziony po EAN-ie.
 * {@code regularPrice} to cena sprzedaży brutto z kartoteki bez trwającej promocji z kasy.
 */
public record LeafletLineResponse(
        UUID id,
        int lineNo,
        String section,
        LocalDate dateFrom,
        LocalDate dateTo,
        String meaCode,
        String itm8,
        String promoItm8,
        String ean,
        String name,
        String brand,
        String promoForm,
        String megaKind,
        String clientPrice,
        BigDecimal price,
        String shelfLabel,
        CatalogProduct product,
        BigDecimal regularPrice,
        UUID linkId) {
}
