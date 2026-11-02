package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Plik CTRLPRIX dla programu sklepu ({@code CTRLPRIX_20261102_112600_GOBARTO_<login>.Traite1}): nowe ceny sprzedaży
 * artykułów z dokumentu dostawcy. W linii EAN na 13 znakach (krótszy dopełniony zerami z lewej) i zaraz za nim cena
 * brutto w groszach na 20 cyfrach (7,99 zł = {@code 00000000000000000799}), CRLF. W nazwie czas zapisu
 * (Europe/Warsaw), hasło dostawcy z listy dostawców i login osoby, która zapisuje (w pliku z programu sklepu
 * w tym miejscu jest {@code ImpEk}).
 */
public final class PriceControlFile {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int PRICE_DIGITS = 20;

    public record Line(String ean, BigDecimal salePrice) {
    }

    private PriceControlFile() {
    }

    public static String fileName(String supplierKeyword, String username) {
        return "CTRLPRIX_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + safe(supplierKeyword) + "_"
                + safe(username) + ".Traite1";
    }

    private static String safe(String part) {
        return part.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** EAN w pliku: 8-13 cyfr dopełnione do 13, GTIN-14 z zerem na początku jako EAN-13; inne kody = {@code null}. */
    public static String ean(String code) {
        String digits = code == null ? "" : code.trim();
        if (digits.matches("0\\d{13}")) {
            digits = digits.substring(1);
        }
        if (!digits.matches("\\d{8,13}") || digits.matches("0+")) {
            return null;
        }
        return "0".repeat(13 - digits.length()) + digits;
    }

    /** Linie bez EAN-u albo bez ceny większej od zera są pomijane. */
    public static List<String> lines(List<Line> lines) {
        return lines.stream()
                .filter(line -> ean(line.ean()) != null && line.salePrice() != null && line.salePrice().signum() > 0)
                .map(line -> ean(line.ean()) + String.format("%0" + PRICE_DIGITS + "d",
                        line.salePrice().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()))
                .toList();
    }

    public static byte[] content(List<String> lines) {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append("\r\n"));
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
