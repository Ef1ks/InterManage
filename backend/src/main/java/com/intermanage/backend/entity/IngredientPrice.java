package com.intermanage.backend.entity;

import java.math.BigDecimal;

/**
 * Cena jednostki składnika (kg, L, szt.) i jej źródło. {@code document} to przyjęcie PZ, gdy cena jest z dostawy.
 * {@link #NONE}, gdy produktu nie było w dostawach, a kartoteka nie ma ceny zakupu.
 */
public record IngredientPrice(BigDecimal unitCost, PriceSource source, Document document) {

    public static final IngredientPrice NONE = new IngredientPrice(null, null, null);
}
