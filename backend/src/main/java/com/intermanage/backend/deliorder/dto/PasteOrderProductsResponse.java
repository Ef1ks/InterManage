package com.intermanage.backend.deliorder.dto;

import java.util.List;

/**
 * Wynik wklejenia listy produktów: formularz po zmianie, ile pozycji doszło, ile już było w kategorii (dostały
 * wklejoną jednostkę) i EAN-y, których nie ma w kartotece sklepu.
 */
public record PasteOrderProductsResponse(OrderFormResponse form, int added, int updated, List<String> notFound) {
}
