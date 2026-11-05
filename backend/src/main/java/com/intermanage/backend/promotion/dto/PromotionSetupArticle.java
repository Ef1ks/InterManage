package com.intermanage.backend.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Artykuł w ustawianiu promocji. {@code code}: kod, którego szukano (bez zer wiodących), {@code ean}: bieżący EAN
 * z kartoteki, ten idzie do pliku .PRO. {@code regularPrice}: cena sprzedaży brutto bez trwającej promocji.
 * {@code lastPurchasePrice}: cena netto sztuki z ostatniego przyjęcia PZ ({@code lastPurchaseDate}), a bez przyjęcia
 * z kartoteki (data {@code null}). {@code draft}: artykuł z pliku CADBEZP czekający na raport kartoteki, bez ITM8.
 */
public record PromotionSetupArticle(String code, String itm8, String ean, String name, String brand,
        BigDecimal vatPercent, BigDecimal regularPrice, BigDecimal lastPurchasePrice, LocalDate lastPurchaseDate,
        boolean draft) {
}
