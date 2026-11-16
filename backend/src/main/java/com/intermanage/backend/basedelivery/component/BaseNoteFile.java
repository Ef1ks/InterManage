package com.intermanage.backend.basedelivery.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plik dostawy z bazy dla programu sklepu, jak plik bazy {@code LIVRJPOL-095_09516018207_00538-10023-26043004.TXT}:
 * linia na artykuł (ITM8) dostawcy, pola po przecinku, tekst w cudzysłowach, ASCII, LF po każdej linii:
 *
 * <pre>
 * "30/04/2026","09516018207","01","00809804","PPF",000025.000,0000002.29,0000003.59,"84460547     ",23.00,"538"
 * </pre>
 *
 * data dostawy, nr bonu, rejon, ITM8 (8 cyfr), PPF sztuki / PPV towar na wagę (kod z etykiety wagi), ilość przyjęta
 * (sztuki albo kg, także 0 = nie przyjechało), cena zakupu netto, cena dla klienta brutto, EAN (EAN-8 dopełniony
 * spacjami, inne zerami z przodu do 13, brak = same zera), VAT %, dostawca bez zer wiodących. Ten sam ITM8 z kilku
 * nośników to jedna linia z sumą ilości. Kolejność jak w pliku bazy: najpierw ITM8 sklepu (od 0), potem ITM8 bazy
 * (od 6), w nich po rejonie.
 */
public final class BaseNoteFile {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final int EAN_LENGTH = 13;

    private BaseNoteFile() {
    }

    /** Linia dokumentu bazy; {@code itm8} jak w pliku bazy (do 8 cyfr), {@code quantity} przyjęta. */
    public record Line(String itm8, String region, BigDecimal quantity, BigDecimal purchasePrice,
            BigDecimal retailPrice, String ean, BigDecimal vatRate) {
    }

    public record File(String fileName, byte[] content, int lineCount) {
    }

    /**
     * Plik jednego dostawcy. Nazwa {@code LIVRJPOL-095_<bon>_<dostawca na 5 cyfrach>-<sklep>-<RRMMDD><dzień
     * tygodnia>}: w pliku bazy z 30.04.2026 (czwartek) końcówka to {@code 26043004}; program sklepu czyta plik po
     * treści, końcówki nazwy nie sprawdzaliśmy.
     */
    public static File create(String storeNumber, String number, LocalDate deliveryDate, String supplierCode,
            List<Line> lines) {
        String supplier = supplierCode.replaceFirst("^0+(?=.)", "");
        Map<String, Line> byItm8 = new LinkedHashMap<>();
        for (Line line : lines) {
            String itm8 = itm8(line.itm8());
            byItm8.merge(itm8, line, (first, next) -> new Line(first.itm8(), first.region(),
                    first.quantity().add(next.quantity()), first.purchasePrice(), first.retailPrice(), first.ean(),
                    first.vatRate()));
        }
        List<Line> merged = new ArrayList<>(byItm8.values());
        merged.sort(Comparator.comparing((Line line) -> itm8(line.itm8()).charAt(0))
                .thenComparing(line -> region(line.region())));
        StringBuilder text = new StringBuilder();
        String date = DATE.format(deliveryDate);
        for (Line line : merged) {
            String ean = ean(line.ean());
            text.append(quoted(date)).append(',')
                    .append(quoted(number)).append(',')
                    .append(quoted(region(line.region()))).append(',')
                    .append(quoted(itm8(line.itm8()))).append(',')
                    .append(quoted(weighted(ean, line.quantity()) ? "PPV" : "PPF")).append(',')
                    .append(number(line.quantity(), 3, 10)).append(',')
                    .append(number(line.purchasePrice(), 2, 10)).append(',')
                    .append(number(line.retailPrice(), 2, 10)).append(',')
                    .append(quoted(ean)).append(',')
                    .append(number(line.vatRate(), 2, 5)).append(',')
                    .append(quoted(supplier)).append('\n');
        }
        String fileName = "LIVRJPOL-095_" + number + "_" + pad(supplier, 5) + "-" + storeNumber + "-"
                + FILE_DATE.format(deliveryDate) + pad(String.valueOf(deliveryDate.getDayOfWeek().getValue()), 2)
                + ".TXT";
        return new File(fileName, text.toString().getBytes(StandardCharsets.US_ASCII), merged.size());
    }

    static String itm8(String itm8) {
        String digits = itm8 == null ? "" : itm8.trim().replaceFirst("^0+(?=\\d{8})", "");
        return pad(digits, 8);
    }

    private static String region(String region) {
        return region == null || region.isBlank() ? "00" : pad(region.trim(), 2);
    }

    /**
     * EAN jak w pliku bazy: poprawny EAN-8 (także zapisany u nas z zerami do 13) dopełniony spacjami z prawej, inne
     * zerami z przodu do 13 cyfr; bez EAN-u 13 zer.
     */
    static String ean(String ean) {
        String digits = ean == null ? "" : ean.replaceAll("\\D", "");
        String trimmed = digits.replaceFirst("^0+", "");
        if (trimmed.length() == 8 && validEan8(trimmed)) {
            return trimmed + " ".repeat(EAN_LENGTH - 8);
        }
        if (trimmed.isEmpty()) {
            return "0".repeat(EAN_LENGTH);
        }
        return digits.length() >= EAN_LENGTH ? digits.substring(digits.length() - EAN_LENGTH) : pad(digits,
                EAN_LENGTH);
    }

    private static boolean validEan8(String code) {
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += (code.charAt(i) - '0') * (i % 2 == 0 ? 3 : 1);
        }
        return (10 - sum % 10) % 10 == code.charAt(7) - '0';
    }

    /** Towar na wagę: kod z etykiety wagi (2 + cyfra 1-9, 13 cyfr) albo ułamkowa ilość. */
    private static boolean weighted(String ean, BigDecimal quantity) {
        return ean.matches("2[1-9]\\d{11}") || quantity.stripTrailingZeros().scale() > 0;
    }

    private static String number(BigDecimal value, int scale, int width) {
        BigDecimal amount = (value == null ? BigDecimal.ZERO : value).setScale(scale, RoundingMode.HALF_UP);
        String plain = amount.abs().toPlainString();
        String sign = amount.signum() < 0 ? "-" : "";
        return sign + pad(plain, width - sign.length());
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : "0".repeat(width - value.length()) + value;
    }

    private static String quoted(String value) {
        return '"' + value.replace("\"", "") + '"';
    }
}
