package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wyszukiwanie i filtry listy w Magazynie i produktach (parametry zapytania). {@code stock}: POSITIVE (na stanie),
 * ZERO (zero albo brak), NEGATIVE; przedziały stanu i rotacji z granicami włącznie. {@code nomenclatures}: kody
 * rejonów, rodzin albo podrodzin (początek kodu podrodziny artykułu). {@code cadencier}: WITH (na bieżącej liście
 * cadencier) albo WITHOUT. {@code sort}: name (domyślnie, najpierw aktywne), itm8, stock, rotation, price;
 * {@code direction}: asc albo desc.
 */
public record ProductListRequest(String query, Integer page, String stock, BigDecimal stockFrom, BigDecimal stockTo,
        BigDecimal rotationFrom, BigDecimal rotationTo, List<String> nomenclatures, String cadencier, String sort,
        String direction) {
}
