package com.intermanage.backend.dataentry.dto;

import java.math.BigDecimal;

/**
 * Pozycja nomenklatury sklepu: kod 2, 4 albo 6 cyfr (rejon, rodzina, podrodzina), nazwa i marża wymagana, która na
 * niej obowiązuje (ustawiona na niej albo wyżej; pusta = bez progu).
 */
public record NomenclatureItem(String code, String name, BigDecimal requiredMargin) {
}
