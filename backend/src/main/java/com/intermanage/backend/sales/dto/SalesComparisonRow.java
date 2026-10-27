package com.intermanage.backend.dto;

/**
 * Wiersz porównania sprzedaży: pozycja nomenklatury (rejon, rodzina, podrodzina) albo artykuł. {@code code} to kod
 * nomenklatury bez spacji ("01", "0101", "010101") albo ITM8 artykułu; pusty kod nomenklatury oznacza sprzedaż bez
 * nomenklatury (artykuł spoza kartoteki). {@code name} jest pusta, gdy kodu nie ma w nomenklaturze sklepu albo
 * artykułu w kartotece. EAN i marka tylko przy artykułach.
 */
public record SalesComparisonRow(
        String code,
        String name,
        String ean,
        String brand,
        SalesFigures previous,
        SalesFigures current) {
}
