package com.intermanage.backend.leaflet.component;

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
import com.intermanage.backend.leaflet.dto.LeafletRow;

/**
 * Plik gazetki z centrali (np. {@code UTRZYMYWANIE_CEN_Gazetka_nr_44_2026_+_ceny.xlsx}): nagłówek z kolumnami LP, ROK,
 * TYDZIEŃ, DATA POCZĄTKU, DATA KOŃCA, NR MEA, ITM 8, ITM 8 PROMO, EANY, PRODUKT, MARKA, FORMA PROMOCJI, RODZAJ
 * MEGAPROMOCJI, …, CENA DLA KLIENTA, Limit dla klienta, CENY DO SPRAWDZENIA W SYSTEMIE … (cena gazetkowa), OZNACZENIE
 * PRZY PÓŁCE; niżej pozycje (jeden EAN w wierszu, ten sam produkt bywa w kilku wierszach z różnymi EAN-ami)
 * przedzielone wierszami działów („PRODUKTY KLUCZOWE”: sama nazwa, bez dat i EAN-u). Kolumny szukamy po początku
 * nazwy bez znaków diakrytycznych. Błędny wiersz odrzuca plik.
 */
@Component
public class LeafletParser {

    static final int MAX_REPORTED_ERRORS = 20;
    /** W tylu pierwszych wierszach szukamy nagłówka. */
    private static final int HEADER_ROWS = 10;

    private static final DateTimeFormatter POLISH_DATE = DateTimeFormatter.ofPattern("d.MM.yyyy");
    /** Dzień 0 dat Excela (liczba dni od 30.12.1899). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** Odczytana gazetka: rok i tydzień (numer gazetki) z pierwszej pozycji i pozycje w kolejności z pliku. */
    public record Parsed(int year, int week, List<LeafletRow> rows) {
    }

    /** Kolumny pliku: indeks w wierszu, -1 = brak kolumny. */
    private record Columns(int lp, int year, int week, int from, int to, int mea, int itm8, int promoItm8, int ean,
            int name, int brand, int promoForm, int megaKind, int clientPrice, int price, int shelfLabel) {
    }

    public Parsed parse(List<List<String>> sheet) throws InvalidReportException {
        int headerRow = -1;
        Columns columns = null;
        for (int r = 0; r < Math.min(HEADER_ROWS, sheet.size()); r++) {
            List<String> keys = sheet.get(r).stream().map(cell -> cell == null ? "" : key(cell)).toList();
            if (keys.contains("eany") && find(keys, "datapoczatku") >= 0) {
                headerRow = r;
                columns = new Columns(find(keys, "lp"), find(keys, "rok"), find(keys, "tydzien"),
                        find(keys, "datapoczatku"), find(keys, "datakonca"), find(keys, "nrmea"), find(keys, "itm8"),
                        find(keys, "itm8promo"), find(keys, "eany"), find(keys, "produkt"), find(keys, "marka"),
                        find(keys, "formapromocji"), find(keys, "rodzajmegapromocji"), find(keys, "cenadlaklienta"),
                        find(keys, "cenydosprawdzenia"), find(keys, "oznaczenie"));
                break;
            }
        }
        if (columns == null) {
            throw new InvalidReportException("To nie jest plik gazetki: brak nagłówka z kolumnami EANY i DATA POCZĄTKU");
        }
        List<String> missing = new ArrayList<>();
        if (columns.to() < 0) {
            missing.add("DATA KOŃCA");
        }
        if (columns.mea() < 0) {
            missing.add("NR MEA");
        }
        if (columns.name() < 0) {
            missing.add("PRODUKT");
        }
        if (columns.price() < 0) {
            missing.add("CENY DO SPRAWDZENIA");
        }
        if (columns.year() < 0 || columns.week() < 0) {
            missing.add("ROK i TYDZIEŃ");
        }
        if (!missing.isEmpty()) {
            throw new InvalidReportException("To nie jest plik gazetki: brak kolumn " + String.join(", ", missing));
        }

        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        List<LeafletRow> rows = new ArrayList<>();
        String section = null;
        Integer year = null;
        Integer week = null;
        for (int r = headerRow + 1; r < sheet.size(); r++) {
            List<String> cells = sheet.get(r);
            String ean = text(cells, columns.ean());
            String from = text(cells, columns.from());
            if (ean == null && from == null) {
                // Wiersz działu („PRODUKTY KLUCZOWE”) albo pusty.
                String name = text(cells, columns.name());
                if (name != null) {
                    section = shorten(oneLine(name), 200);
                }
                continue;
            }
            try {
                if (ean == null) {
                    throw new IllegalArgumentException("brak EAN-u");
                }
                LeafletRow row = row(cells, columns, rows.size() + 1, section);
                if (year == null) {
                    year = integer(required(cells, columns.year(), "ROK"), "ROK");
                    week = integer(required(cells, columns.week(), "TYDZIEŃ"), "TYDZIEŃ");
                }
                rows.add(row);
            } catch (IllegalArgumentException e) {
                if (++errorCount <= MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        if (errorCount > 0) {
            throw new InvalidReportException("Plik ma błędne wiersze", errors, errorCount);
        }
        if (rows.isEmpty()) {
            throw new InvalidReportException("Plik nie ma żadnej pozycji gazetki");
        }
        return new Parsed(year, week, rows);
    }

    private static LeafletRow row(List<String> cells, Columns columns, int lineNo, String section) {
        String ean = code(required(cells, columns.ean(), "EANY"));
        if (!ean.matches("\\d{1,20}")) {
            throw new IllegalArgumentException("EAN „" + ean + "” to nie kod z cyfr");
        }
        LocalDate from = date(required(cells, columns.from(), "DATA POCZĄTKU"), "DATA POCZĄTKU");
        LocalDate to = date(required(cells, columns.to(), "DATA KOŃCA"), "DATA KOŃCA");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("data końca przed datą początku");
        }
        String mea = text(cells, columns.mea());
        String itm8 = text(cells, columns.itm8());
        String promoItm8 = text(cells, columns.promoItm8());
        String clientPrice = text(cells, columns.clientPrice());
        return new LeafletRow(
                lineNo,
                section,
                from,
                to,
                mea == null ? null : shorten(code(mea).toUpperCase(Locale.ROOT), 20),
                itm8 == null ? null : shorten(code(itm8), 13),
                promoItm8 == null ? null : shorten(code(promoItm8), 13),
                ean,
                shorten(oneLine(required(cells, columns.name(), "PRODUKT")), 500),
                shorten(oneLine(text(cells, columns.brand())), 200),
                oneLine(text(cells, columns.promoForm())),
                shorten(oneLine(text(cells, columns.megaKind())), 100),
                clientPrice == null ? null : shorten(clientPrice, 50),
                price(text(cells, columns.price())),
                shorten(oneLine(text(cells, columns.shelfLabel())), 300));
    }

    /**
     * Nazwa kolumny same litery i cyfry, bez znaków diakrytycznych i wielkości liter ({@code DATA POCZĄTKU} =
     * {@code datapoczatku}, {@code ITM 8 PROMO} = {@code itm8promo}).
     */
    static String key(String header) {
        return Normalizer.normalize(header.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Kolumna o tej nazwie, a bez niej pierwsza, która się nią zaczyna; -1 = brak. */
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
        String value = cells.get(index).replace(' ', ' ').strip();
        return value.isEmpty() ? null : value;
    }

    private static String required(List<String> cells, int index, String column) {
        String value = text(cells, index);
        if (value == null) {
            throw new IllegalArgumentException("brak wartości w kolumnie " + column);
        }
        return value;
    }

    /** Kod z liczby w Excelu: bez części ułamkowej z samych zer ({@code 667810.0} = {@code 667810}). */
    private static String code(String value) {
        return value.strip().replaceAll("\\.0+$", "");
    }

    /** Wiele linii i twardych spacji z komórki w jednej linii z pojedynczymi spacjami. */
    static String oneLine(String value) {
        if (value == null) {
            return null;
        }
        String line = value.replace(' ', ' ').replaceAll("\\s+", " ").strip();
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

    /** Cena gazetkowa: liczba z kropką albo przecinkiem; od -1 do 0 to rabat (-0,2 = -20%). */
    private static BigDecimal price(String value) {
        if (value == null) {
            return null;
        }
        BigDecimal price;
        try {
            price = new BigDecimal(value.replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cena „" + value + "” to nie liczba");
        }
        if (price.compareTo(BigDecimal.ONE.negate()) <= 0) {
            throw new IllegalArgumentException("cena „" + value + "” jest ujemna");
        }
        // Excel zapisuje 8,29 jako 8.2899999999999991: cena do grosza, rabat (-0,2) do czterech miejsc.
        return price.setScale(price.signum() < 0 ? 4 : 2, RoundingMode.HALF_UP);
    }

    static LocalDate date(String value, String column) {
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
