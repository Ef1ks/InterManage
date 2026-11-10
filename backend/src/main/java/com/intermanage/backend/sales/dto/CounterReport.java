package com.intermanage.backend.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Raport lady: sprzedaż rejonów lady (mięso 24, garmaż 36, drób 46) w kilku sklepach naraz: rejony, rodziny,
 * podrodziny i artykuły. Listy {@code values} są w kolejności {@link #stores}, a brak sprzedaży w sklepie to
 * {@code null}.
 */
public record CounterReport(LocalDate from, LocalDate to, List<String> regionCodes, List<Store> stores,
        List<Figures> total, List<Region> regions) {

    /** Sklep w raporcie: pierwszy to sklep, z którego oglądamy; {@code lastSaleDate} = do kiedy wgrano sprzedaż. */
    public record Store(UUID id, String number, String name, LocalDate lastSaleDate) {
    }

    /**
     * Sumy pozycji w sklepie. Marża % od sprzedaży netto, udział = sprzedaż brutto pozycji w sprzedaży brutto
     * wybranych rejonów lady w sklepie (porównuje strukturę sklepów różnej wielkości).
     */
    public record Figures(BigDecimal quantity, BigDecimal costValue, BigDecimal saleValueGross,
            BigDecimal saleValueNet, BigDecimal marginValue, BigDecimal marginPercent, BigDecimal sharePercent) {
    }

    public record Region(String code, String name, List<Figures> values, List<Family> families) {
    }

    public record Family(String code, String name, List<Figures> values, List<Subfamily> subfamilies) {
    }

    public record Subfamily(String code, String name, List<Figures> values, List<Article> articles) {
    }

    public record Article(String itm8, String name, String brand, List<Figures> values) {
    }

    /** Sklep do wyboru: dostępny użytkownikowi albo zaprzyjaźniony ({@code partner}). */
    public record Option(UUID id, String number, String name, boolean partner) {
    }

    /** Miary raportu (kolumny); sprzedaż brutto albo netto wybiera {@link Basis}. */
    public enum Metric {
        QUANTITY, COST, SALE, MARGIN, MARGIN_PERCENT, SHARE
    }

    public enum Basis {
        GROSS, NET
    }
}
