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
     * Sumy pozycji w sklepie. Marża jest po likwidacjach: sprzedaż netto − zakup FIFO − {@code lossValue}
     * (likwidacje PL z okresu, wartość zakupu netto), % od sprzedaży brutto. Udział = sprzedaż brutto pozycji w
     * sprzedaży brutto wybranych rejonów lady w sklepie (porównuje strukturę sklepów różnej wielkości).
     */
    public record Figures(BigDecimal quantity, BigDecimal costValue, BigDecimal saleValueGross,
            BigDecimal saleValueNet, BigDecimal marginValue, BigDecimal marginPercent, BigDecimal sharePercent,
            BigDecimal lossValue) {
    }

    public record Region(String code, String name, List<Figures> values, List<Family> families) {
    }

    public record Family(String code, String name, List<Figures> values, List<Subfamily> subfamilies) {
    }

    public record Subfamily(String code, String name, List<Figures> values, List<Article> articles) {
    }

    /**
     * Wiersz artykułu: {@code itm8} to klucz wiersza (artykuł pierwszego sklepu, w którym jest), a {@code sources}
     * mówi, który artykuł sprzedał każdy sklep (kolejność {@link #stores}, {@code null} = brak sprzedaży). Inny ITM8
     * niż wiersz albo {@code linkId} = artykuł dołączony połączeniem między sklepami.
     */
    public record Article(String itm8, String name, String brand, List<Figures> values, List<Source> sources) {
    }

    /** Artykuł sklepu w wierszu: ITM8 i nazwa z jego kartoteki, {@code linkId} = połączenie, które go dołączyło. */
    public record Source(String itm8, String name, UUID linkId) {
    }

    /** Sklep do wyboru: dostępny użytkownikowi albo zaprzyjaźniony ({@code partner}). */
    public record Option(UUID id, String number, String name, boolean partner) {
    }

    /** Miary raportu (kolumny); sprzedaż brutto albo netto wybiera {@link Basis}. */
    public enum Metric {
        QUANTITY, COST, SALE, MARGIN, MARGIN_PERCENT, SHARE, LOSSES
    }

    public enum Basis {
        GROSS, NET
    }
}
