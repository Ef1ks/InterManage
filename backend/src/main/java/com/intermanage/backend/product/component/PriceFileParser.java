package com.intermanage.backend.product.component;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.product.dto.ParsedPriceFile;
import com.intermanage.backend.product.dto.PriceFileLine;

/**
 * Plik TRACEART z systemu sklepu ({@code TRACEART_20261102_075559_SR0AF901_UT0AF907.Traite0}): zmiany cen na kasie,
 * jedna linia na każdy kod EAN artykułu, 360 znaków stałej szerokości (windows-1250, NUL jako separator, CRLF). Pola
 * (od zera) rozpoznane na plikach z 02.11.2026 i sprawdzone z kartoteką sklepu 10023:
 * <ul>
 * <li>0-12 EAN, 14-38 nazwa, 39 kod stawki VAT (jak {@code products.vat_code}),</li>
 * <li>41-49 cena sprzedaży brutto obowiązująca na kasie (w promocji cena promocyjna),</li>
 * <li>120-125 data początku ceny / promocji (DMMRR wyrównane do prawej, np. " 21126" = 02.11.2026),</li>
 * <li>126-131 data końca promocji ("     0" = bez promocji),</li>
 * <li>133-145 ITM8 (13 cyfr),</li>
 * <li>306-314 cena regularna w promocji ("999999.99" poza promocją).</li>
 * </ul>
 * Kody z etykiet obniżek (13…, 15…, 17…) mają niższe ceny, więc cenę artykułu wybiera serwis (kod główny albo
 * najwyższa cena), parser oddaje wszystkie linie. Linia, której nie da się odczytać, jest pomijana.
 */
@Component
public class PriceFileParser {

    private static final Charset CHARSET = Charset.forName("windows-1250");
    private static final Pattern FILE_NAME =
            Pattern.compile("^TRACEART_(\\d{8})_(\\d{6})(?:_.*)?$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final DateTimeFormatter LINE_DATE = DateTimeFormatter.ofPattern("ddMMyy", Locale.ROOT);
    private static final BigDecimal NO_PRICE = new BigDecimal("999999.99");
    private static final int MIN_LENGTH = 315;

    /** Czas z nazwy pliku; {@code null}, gdy to nie jest plik TRACEART. */
    public static LocalDateTime fileTime(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(1) + matcher.group(2), FILE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public ParsedPriceFile parse(String fileName, byte[] content) {
        LocalDateTime time = fileTime(fileName);
        if (time == null) {
            throw new IllegalArgumentException("To nie jest plik TRACEART: " + fileName);
        }
        List<PriceFileLine> lines = new ArrayList<>();
        int total = 0;
        for (String line : new String(content, CHARSET).split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            total++;
            PriceFileLine parsed = parseLine(line);
            if (parsed != null) {
                lines.add(parsed);
            }
        }
        return new ParsedPriceFile(time, total, lines);
    }

    private static PriceFileLine parseLine(String line) {
        if (line.length() < MIN_LENGTH) {
            return null;
        }
        String ean = line.substring(0, 13).trim();
        String itm8 = line.substring(133, 146);
        BigDecimal price = decimal(line.substring(41, 50));
        if (!ean.matches("\\d{1,13}") || !itm8.matches("\\d{13}") || price == null) {
            return null;
        }
        LocalDate promoTo = date(line.substring(126, 132));
        BigDecimal regular = decimal(line.substring(306, 315));
        if (promoTo == null || regular == null || regular.compareTo(NO_PRICE) == 0) {
            return new PriceFileLine(itm8, ean, price, null, null, null);
        }
        return new PriceFileLine(itm8, ean, regular, price, date(line.substring(120, 126)), promoTo);
    }

    private static BigDecimal decimal(String text) {
        String value = text.trim();
        if (!value.matches("\\d+\\.\\d{2}")) {
            return null;
        }
        return new BigDecimal(value);
    }

    /** DMMRR wyrównane do prawej spacjami; "0" albo pusto = brak daty. */
    private static LocalDate date(String text) {
        String value = text.trim();
        if (value.isEmpty() || value.equals("0") || !value.matches("\\d{5,6}")) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() == 5 ? "0" + value : value, LINE_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
