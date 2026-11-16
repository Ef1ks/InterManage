package com.intermanage.backend.mea.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.exception.InvalidReportException;

/**
 * Opis gazetki z centrali (np. {@code Gazetka nr 50_2026 BOŻE NARODZENIE 3.xlsx}, arkusz „Arkusz”): nagłówek w pierwszym
 * wierszu z kolumnami LP, ROK, TYDZIEŃ, DATA POCZĄTKU, DATA KOŃCA, NR MEA, ITM 8, ITM 8 PROMO, PRODUKT, MARKA, FORMA
 * PROMOCJI, RODZAJ i WARIANT MEGAPROMOCJI, ILOŚĆ SZTUK i CENA W MEGAPROMOCJI WIELOSZTUKI, CENA CESJI DO ROZLICZEŃ, CENA
 * DLA KLIENTA, Limit dla klienta, FORMA DOSTAWY; pozycje przedzielone wierszami działów (sama nazwa w PRODUKT, bez
 * dat). Kolumny po początku nazwy bez znaków diakrytycznych, jak w pliku „UTRZYMYWANIE CEN”. Błędny wiersz odrzuca plik.
 */
@Component
public class MeaLeafletParser {

    static final int MAX_REPORTED_ERRORS = 20;
    private static final int HEADER_ROWS = 10;
    private static final DateTimeFormatter POLISH_DATE = DateTimeFormatter.ofPattern("d.MM.yyyy");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    public record Parsed(int year, int week, List<Line> lines) {
    }

    /** Pozycja opisu; {@code promoForm} to oznaczenia z FORMA PROMOCJI, po jednym na linię komórki. */
    public record Line(int lp, String section, LocalDate dateFrom, LocalDate dateTo, String meaNumber, String itm8,
            String promoItm8, String name, String brand, List<String> promoForm, String megaKind, String megaVariant,
            Integer megaQuantity, BigDecimal megaPrice, BigDecimal purchasePrice, BigDecimal clientPrice,
            Integer clientLimit, String deliveryForm) {
    }

    private record Columns(int year, int week, int from, int to, int mea, int itm8, int promoItm8, int name, int brand,
            int promoForm, int megaKind, int megaVariant, int megaQuantity, int megaPrice, int purchasePrice,
            int clientPrice, int clientLimit, int deliveryForm) {
    }

    public Parsed parse(List<List<String>> sheet) throws InvalidReportException {
        int headerRow = -1;
        Columns columns = null;
        for (int r = 0; r < Math.min(HEADER_ROWS, sheet.size()); r++) {
            List<String> keys = sheet.get(r).stream().map(cell -> cell == null ? "" : key(cell)).toList();
            if (keys.contains("itm8promo") && find(keys, "formapromocji") >= 0) {
                headerRow = r;
                columns = new Columns(find(keys, "rok"), find(keys, "tydzien"), find(keys, "datapoczatku"),
                        find(keys, "datakonca"), find(keys, "nrmea"), keys.indexOf("itm8"), keys.indexOf("itm8promo"),
                        find(keys, "produkt"), find(keys, "marka"), find(keys, "formapromocji"),
                        find(keys, "rodzajmegapromocji"), find(keys, "wariantmegapromocji"),
                        find(keys, "iloscsztuk"), find(keys, "cenawmegapromocji"), find(keys, "cenacesji"),
                        find(keys, "cenadlaklienta"), find(keys, "limitdlaklienta"), find(keys, "formadostawy"));
                break;
            }
        }
        if (columns == null) {
            throw new InvalidReportException(
                    "To nie jest opis gazetki: brak nagłówka z kolumnami ITM 8 PROMO i FORMA PROMOCJI");
        }
        List<String> missing = new ArrayList<>();
        if (columns.from() < 0 || columns.to() < 0) {
            missing.add("DATA POCZĄTKU i DATA KOŃCA");
        }
        if (columns.name() < 0) {
            missing.add("PRODUKT");
        }
        if (columns.year() < 0 || columns.week() < 0) {
            missing.add("ROK i TYDZIEŃ");
        }
        if (!missing.isEmpty()) {
            throw new InvalidReportException("To nie jest opis gazetki: brak kolumn " + String.join(", ", missing));
        }

        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        List<Line> lines = new ArrayList<>();
        String section = null;
        Integer year = null;
        Integer week = null;
        for (int r = headerRow + 1; r < sheet.size(); r++) {
            List<String> cells = sheet.get(r);
            if (text(cells, columns.from()) == null && text(cells, columns.to()) == null) {
                // Wiersz działu („PRODUKTY SUCHE”) albo pusty.
                String name = oneLine(text(cells, columns.name()));
                if (name != null) {
                    section = shorten(name, 200);
                }
                continue;
            }
            try {
                lines.add(line(cells, columns, lines.size() + 1, section));
                if (year == null) {
                    year = integer(required(cells, columns.year(), "ROK"), "ROK");
                    week = integer(required(cells, columns.week(), "TYDZIEŃ"), "TYDZIEŃ");
                }
            } catch (IllegalArgumentException e) {
                if (++errorCount <= MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        if (errorCount > 0) {
            throw new InvalidReportException("Plik ma błędne wiersze", errors, errorCount);
        }
        if (lines.isEmpty()) {
            throw new InvalidReportException("Plik nie ma żadnej pozycji gazetki");
        }
        return new Parsed(year, week, lines);
    }

    private static Line line(List<String> cells, Columns columns, int lp, String section) {
        LocalDate from = date(required(cells, columns.from(), "DATA POCZĄTKU"), "DATA POCZĄTKU");
        LocalDate to = date(required(cells, columns.to(), "DATA KOŃCA"), "DATA KOŃCA");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("data końca przed datą początku");
        }
        String mea = text(cells, columns.mea());
        return new Line(
                lp,
                section,
                from,
                to,
                mea == null ? null : shorten(code(mea).toUpperCase(Locale.ROOT), 20),
                itm8(text(cells, columns.itm8())),
                itm8(text(cells, columns.promoItm8())),
                shorten(oneLine(required(cells, columns.name(), "PRODUKT")), 500),
                shorten(oneLine(text(cells, columns.brand())), 200),
                promoForm(text(cells, columns.promoForm())),
                shorten(oneLine(text(cells, columns.megaKind())), 100),
                shorten(oneLine(text(cells, columns.megaVariant())), 100),
                count(text(cells, columns.megaQuantity()), "ILOŚĆ SZTUK W MEGAPROMOCJI"),
                price(text(cells, columns.megaPrice()), "CENA W MEGAPROMOCJI"),
                price(text(cells, columns.purchasePrice()), "CENA CESJI"),
                price(text(cells, columns.clientPrice()), "CENA DLA KLIENTA"),
                count(text(cells, columns.clientLimit()), "Limit dla klienta"),
                shorten(oneLine(text(cells, columns.deliveryForm())), 50));
    }

    /** ITM8 bez zer wiodących; tekst zamiast kodu („DDD”) to brak. */
    private static String itm8(String value) {
        if (value == null) {
            return null;
        }
        String digits = code(value).replaceFirst("^0+", "");
        return digits.matches("\\d{1,13}") ? digits : null;
    }

    /** Oznaczenia z komórki, po jednym na linię, bez pustych i bez powtórzeń. */
    static List<String> promoForm(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> marks = new ArrayList<>();
        for (String part : value.split("\\R")) {
            String mark = oneLine(part);
            if (mark != null && !marks.contains(mark)) {
                marks.add(shorten(mark, 500));
            }
        }
        return marks;
    }

    static String key(String header) {
        return Normalizer.normalize(header.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int find(List<String> keys, String name) {
        int exact = keys.indexOf(name);
        if (exact >= 0) {
            return exact;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).startsWith(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String text(List<String> cells, int index) {
        if (index < 0 || index >= cells.size() || cells.get(index) == null) {
            return null;
        }
        String value = cells.get(index).replace(' ', ' ').strip();
        return value.isEmpty() ? null : value;
    }

    private static String required(List<String> cells, int index, String column) {
        String value = text(cells, index);
        if (value == null) {
            throw new IllegalArgumentException("brak wartości w kolumnie " + column);
        }
        return value;
    }

    private static String code(String value) {
        return value.strip().replaceAll("\\.0+$", "");
    }

    private static String oneLine(String value) {
        if (value == null) {
            return null;
        }
        String line = value.replace(' ', ' ').replaceAll("\\s+", " ").strip();
        return line.isEmpty() ? null : line;
    }

    private static String shorten(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static int integer(String value, String column) {
        try {
            return Integer.parseInt(code(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " „" + value + "” to nie liczba");
        }
    }

    /** Liczba sztuk (ilość w megapromocji, limit): pusta = brak, tekst bez liczby też (uwagi zamiast wartości). */
    private static Integer count(String value, String column) {
        if (value == null) {
            return null;
        }
        String digits = code(value).replaceAll("(?i)\\s*szt\\.?$", "");
        if (!digits.matches("\\d{1,6}")) {
            return null;
        }
        return Integer.parseInt(digits);
    }

    /** Cena z przecinkiem albo kropką („17,98”); do grosza, bo Excel zapisuje 8,29 jako 8.2899999999999991. */
    private static BigDecimal price(String value, String column) {
        if (value == null) {
            return null;
        }
        String number = value.replace(" ", "").replace(" ", "").replaceAll("(?i)zł$", "").replace(',', '.');
        BigDecimal price;
        try {
            price = new BigDecimal(number);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " „" + value + "” to nie cena");
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException(column + " „" + value + "” jest ujemna");
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate date(String value, String column) {
        String text = value.strip();
        if (text.matches("\\d{1,6}(\\.0+)?")) {
            return EXCEL_EPOCH.plusDays(Long.parseLong(text.replaceAll("\\..*", "")));
        }
        try {
            return text.contains(".") ? LocalDate.parse(text, POLISH_DATE) : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " „" + text + "” to nie data");
        }
    }
}
