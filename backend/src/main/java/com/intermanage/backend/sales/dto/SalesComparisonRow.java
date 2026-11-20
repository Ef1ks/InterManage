package com.intermanage.backend.sales.dto;

import java.util.List;

/**
 * Wiersz porównania sprzedaży: pozycja nomenklatury (rejon, rodzina, podrodzina) albo artykuł. {@code code} to kod
 * nomenklatury bez spacji ("01", "0101", "010101") albo ITM8 artykułu; pusty kod nomenklatury oznacza sprzedaż bez
 * nomenklatury (artykuł spoza kartoteki). {@code name} jest pusta, gdy kodu nie ma w nomenklaturze sklepu albo
 * artykułu w kartotece. EAN i marka tylko przy artykułach. {@code other} to sprzedaż drugiego sklepu w aktualnym
 * okresie, gdy porównujemy z innym sklepem (bez porównania pusta; pozycja, której nasz sklep nie sprzedał, ma u nas
 * zera). {@code leaflets} to gazetki artykułu nachodzące na którykolwiek z okresów (przy nomenklaturze puste).
 */
public record SalesComparisonRow(
        String code,
        String name,
        String ean,
        String brand,
        SalesFigures previous,
        SalesFigures current,
        SalesFigures other,
        List<ArticleLeafletMark> leaflets) {

    public SalesComparisonRow(String code, String name, String ean, String brand, SalesFigures previous,
            SalesFigures current, SalesFigures other) {
        this(code, name, ean, brand, previous, current, other, List.of());
    }

    public SalesComparisonRow(String code, String name, String ean, String brand, SalesFigures previous,
            SalesFigures current) {
        this(code, name, ean, brand, previous, current, null);
    }

    public SalesComparisonRow withLeaflets(List<ArticleLeafletMark> leaflets) {
        return new SalesComparisonRow(code, name, ean, brand, previous, current, other, leaflets);
    }
}
