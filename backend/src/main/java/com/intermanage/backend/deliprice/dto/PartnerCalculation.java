package com.intermanage.backend.deliprice.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Kalkulacja sklepu zaprzyjaźnionego widziana z drugiego sklepu: wyrób sąsiada z jego ceną sprzedaży
 * ({@code withoutRecipe}: cena z półki, bo nie ma przepisu) i artykuł, do którego sklep go przypisał.
 */
public record PartnerCalculation(
        UUID calculationId,
        UUID sourceProductId,
        String name,
        String ean,
        BigDecimal salePrice,
        short vatRate,
        boolean withoutRecipe,
        Link link) {

    /** Własny artykuł: {@code salePrice} to jego cena na półce, {@code auto}: z automatycznego przypisania. */
    public record Link(UUID id, boolean auto, String itm8, String ean, String name, String brand, BigDecimal salePrice) {
    }
}
