package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Filtry raportu niesprzedających się artykułów (parametry zapytania). {@code from}, {@code to}: okres liczenia
 * rotacji. {@code maxRotation}: najwyższa rotacja tygodniowa, włącznie. {@code nomenclatures}: kody rejonów, rodzin
 * albo podrodzin. {@code category}: id kategorii własnej albo OTHER (rejony spoza kategorii). {@code supplier}: kod
 * ostatniego dostawcy. {@code cadencier}: WITH albo WITHOUT. {@code origin}: BASE albo OUTSIDE, czy ostatnia dostawa
 * była z bazy. {@code minStockValue}: najmniejsza wartość stanu.
 * {@code withoutDeliveries}: bez artykułów, które dostały dostawę w okresie, bo nie miały kiedy się sprzedać.
 * {@code withRawMaterials}: także rejony surowców do produkcji, ukryte, dopóki nie wybrano nomenklatury.
 * {@code deliveryDays}: 1, 7 albo 30, raport pokazuje wtedy przyjęcia z tylu ostatnich dni z takimi artykułami.
 * {@code sort}: value (domyślnie), name, itm8, supplier, delivery, stock, sold, rotation; {@code direction}: asc, desc.
 */
public record SlowMovingRequest(LocalDate from, LocalDate to, BigDecimal maxRotation, List<String> nomenclatures,
        String category, String supplier, String cadencier, String origin, BigDecimal minStockValue, String query,
        Boolean withoutDeliveries, Boolean withRawMaterials, Integer deliveryDays, String sort, String direction) {
}
