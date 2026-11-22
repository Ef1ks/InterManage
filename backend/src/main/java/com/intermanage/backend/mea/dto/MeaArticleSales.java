package com.intermanage.backend.mea.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sprzedaż dzienna artykułów dokumentu MEA w ostatnich tygodniach czwartek-środa (tydzień gazetkowy) i okresy, w których
 * artykuł był w promocji. Tygodnie układa front z dni; {@code to} to środa kończąca ostatni tydzień, a dni po
 * {@code lastSaleDate} jeszcze nie mają wgranej sprzedaży.
 */
public record MeaArticleSales(LocalDate from, LocalDate to, LocalDate lastSaleDate, List<Article> articles) {

    /** Artykuł po ITM8 z kartoteki (13 cyfr); dni bez sprzedaży nie ma w {@code days}. */
    public record Article(String itm8, List<Day> days, List<Promo> promos) {
    }

    /**
     * Sprzedaż dnia (suma po EAN-ach); {@code priceDrop} = cena dnia poniżej 85% ceny zwykłej, czyli promocja
     * rozpoznana ze sprzedaży (jak w skuteczności promocji).
     */
    public record Day(LocalDate date, BigDecimal quantity, BigDecimal gross, boolean priceDrop) {
    }

    /**
     * Promocja artykułu nachodząca na okres: {@code LIST} lista promocji, {@code TILL} promocja z kasy, {@code LEAFLET}
     * gazetka sklepu z pliku, {@code MEA} formularz MEA ({@code label} = numer MEA).
     */
    public record Promo(String source, String label, LocalDate from, LocalDate to) {
    }
}
