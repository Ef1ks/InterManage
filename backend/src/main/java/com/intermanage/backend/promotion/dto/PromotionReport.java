package com.intermanage.backend.promotion.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Skuteczność promocji kończących się w okresie {@code from}-{@code to} (do ostatniego dnia sprzedaży).
 * {@code listSince}: od kiedy sklep wgrywa listę promocji (wcześniejsze promocje są tylko rozpoznane ze sprzedaży,
 * {@code null} = listy nie wgrano). {@code leafletsSince}: pierwszy dzień promocji z wgranych gazetek (artykuły
 * w gazetce wypadają z raportu, wcześniej raport ich nie rozpozna; {@code null} = żadnej nie wgrano).
 * {@code noReactionUplift}, {@code baseDays}, {@code afterDays}: progi raportu do opisów na ekranie.
 */
public record PromotionReport(LocalDate from, LocalDate to, LocalDate lastSaleDate, LocalDate listSince,
        LocalDate leafletsSince, BigDecimal noReactionUplift, int baseDays, int afterDays, List<Promotion> promotions) {

    /**
     * Jedna promocja artykułu. {@code listed}: z listy promocji, inaczej rozpoznana ze sprzedaży (cena zwykła to
     * wtedy cena odniesienia z miesięcy przed promocją, a cena promocyjna to średnia cena w promocji). Ilość, sprzedaż
     * i marża (FIFO) są z całej promocji, dzienne wartości bazy z dni bez promocji przed nią.
     * <p>
     * {@code uplift}: ile razy więcej sprzedawało się dziennie niż w bazie. {@code breakEvenUplift}: ile razy więcej
     * trzeba było sprzedawać, żeby marża zł wyszła jak bez promocji (marża na sztuce w bazie / w promocji; {@code null},
     * gdy promocja jest poniżej kosztu albo baza nie ma marży). {@code extraMargin}: marża w promocji minus marża,
     * którą artykuł dałby w tych dniach bez promocji. {@code afterRatio}: sprzedaż dzienna po promocji względem bazy
     * (gdy jest co najmniej 7 dni po). {@code lossValue}: likwidacje w trakcie i po promocji, {@code usualLossValue}:
     * ile likwidacji byłoby w tylu dniach według bazy.
     */
    public record Promotion(String itm8, String ean, String name, String brand, String regionCode, String regionName,
            boolean listed, LocalDate startDate, LocalDate endDate, int days, BigDecimal regularPrice,
            BigDecimal promoPrice, BigDecimal discountPercent, BigDecimal listMarginPercent, BigDecimal quantity,
            BigDecimal saleValueGross, BigDecimal marginValue, BigDecimal marginPercent, int baseDays,
            BigDecimal baseQuantityPerDay, BigDecimal baseMarginPerDay, BigDecimal baseMarginPercent,
            BigDecimal quantityPerDay, BigDecimal uplift, BigDecimal breakEvenUplift, BigDecimal extraMargin,
            int afterDays, BigDecimal afterRatio, BigDecimal lossValue, BigDecimal usualLossValue,
            PromotionVerdict verdict) {
    }
}
