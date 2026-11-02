package com.intermanage.backend.meat.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Porównanie cen mięsa w sklepach. Wiersz to artykuł (po ITM8, ten sam we wszystkich sklepach) albo pozycja bez
 * artykułu (po nazwie z WZ), w trybie {@code SPLIT} osobno dla każdego dnia dostawy. Kolory, sumy i marże liczy front.
 */
public record MeatReport(LocalDate from, LocalDate to, String mode, List<Store> stores, List<Row> rows) {

    public record Store(UUID id, String number, String name) {
    }

    /**
     * @param itm8       pusty przy pozycji bez artykułu (sam zakup: bez ceny sprzedaży)
     * @param date       dzień dostawy w trybie {@code SPLIT}, inaczej pusty
     * @param vatPercent stawka VAT artykułu do ceny sprzedaży netto (mięso 5%)
     */
    public record Row(String key, String itm8, String name, String brand, LocalDate date, BigDecimal vatPercent,
            List<Cell> cells) {
    }

    /**
     * Sklep w wierszu, w kolejności {@link MeatReport#stores}.
     *
     * @param purchasePrice średnia cena zakupu netto za kg z WZ; pusta = sklep nie kupił
     * @param purchaseCount ile pozycji WZ złożyło się na średnią
     * @param salePrice     cena sprzedaży brutto za kg: sprzedaż ÷ ilość z dnia dostawy (średnia z dni dostaw);
     *                      bez sprzedaży tego dnia albo bez zakupu ostatnia znana
     * @param saleFallback  {@code salePrice} to ostatnia znana cena (szara), z dnia {@code saleDate}
     */
    public record Cell(UUID storeId, BigDecimal purchasePrice, int purchaseCount, BigDecimal salePrice,
            LocalDate saleDate, boolean saleFallback) {
    }
}
