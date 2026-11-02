package com.intermanage.backend.product.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.product.dto.ParsedProductCodeReport;
import com.intermanage.backend.product.dto.ProductCodeReportRow;

/**
 * Czyta multikody, raport "Szczegóły informacji kodu sprzedaży" z systemu sklepu (np. Multikody.xlsx, arkusz Mercalys,
 * ok. 86 tys. wierszy): "TRYTOMA Sp. z o.o. - Numer punktu sprzedaży 10023", "Data edycji: 02/11/2026 - 06:38",
 * wiersz nagłówka (Kod artykułu, Kod EAN13, Nazwa, Prio, Jedn.Sprzedaży, Opak.sprzedaż, Współcz. ceny,
 * CenaSprzedaży, Data usunięcia, Kaucja...), po jednej parze ITM8 + EAN w wierszu i na końcu "Ilość linii: : 86430".
 * Kolumny szukamy po nazwie; obowiązkowe są tylko kody. Powtórzona para albo jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class ProductCodeReportParser {

    private final XlsxReader xlsxReader;

    public ProductCodeReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedProductCodeReport parse(Path file) throws IOException, InvalidReportException {
        ReportReader reader = new ReportReader();
        try {
            xlsxReader.readFirstSheet(file, reader::onRow);
        } catch (InvalidWorkbookException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku. Wgraj raport zapisany jako .xlsx lub .xlsm.");
        }
        return reader.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        private static final Pattern STORE_NUMBER = Pattern.compile("numer punktu sprzedazy (\\d{3,})");
        /** "Data edycji: 02/11/2026 - 06:38". */
        private static final Pattern GENERATED_AT = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{4})\\s*-?\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)");
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm[:ss]");
        /** "Ilość linii: : 86430" po normalizacji. */
        private static final Pattern DECLARED_ROWS = Pattern.compile("^ilosc linii (\\d+)");
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final Pattern DAY = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
        /** Excel liczy dni od 30.12.1899 (z błędem roku przestępnego 1900, który nas nie dotyczy). */
        private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
        /** Jednostka "bez jednostki" z systemu sklepu. */
        private static final String NO_UNIT = "LIBELLE_UNITEVENTE_NONE";
        private static final int ITM8_LENGTH = 13;

        private static final String ITM = "kod artykulu";
        private static final String EAN = "kod ean13";
        private static final String NAME = "nazwa";
        private static final String PRIO = "prio";
        private static final String UNIT = "jedn sprzedazy";
        private static final String PACK = "opak sprzedaz";
        private static final String FACTOR = "wspolcz ceny";
        private static final String PRICE = "cenasprzedazy";
        private static final String PRICE_SPACED = "cena sprzedazy";
        private static final String REMOVAL = "data usuniecia";
        private static final String DEPOSIT = "kaucja";

        private String storeNumber;
        private LocalDateTime generatedAt;
        private Map<String, Integer> columns;
        private Integer declaredRows;
        private int fileRows;
        private final List<ProductCodeReportRow> rows = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readPreamble(cells);
                return;
            }
            if (declaredRows != null || isBlank(cells)) {
                return;
            }
            Matcher footer = DECLARED_ROWS.matcher(ReportText.normalize(firstNonBlank(cells)));
            if (footer.find()) {
                declaredRows = Integer.parseInt(footer.group(1));
                return;
            }
            fileRows++;
            try {
                String itm8 = itm8(cell(cells, ITM));
                String ean = ean(cell(cells, EAN));
                if (!seen.add(itm8 + "|" + ean)) {
                    throw new IllegalArgumentException("para ITM8 " + itm8 + " i EAN " + ean
                            + " występuje w pliku więcej niż raz");
                }
                String name = cell(cells, NAME);
                if (name != null && name.length() > 100) {
                    throw new IllegalArgumentException("nazwa jest dłuższa niż 100 znaków");
                }
                String unit = cell(cells, UNIT);
                if (unit != null && unit.length() > 30) {
                    throw new IllegalArgumentException("jednostka \"" + unit + "\" jest dłuższa niż 30 znaków");
                }
                String price = cell(cells, PRICE) != null ? cell(cells, PRICE) : cell(cells, PRICE_SPACED);
                rows.add(new ProductCodeReportRow(itm8, ean, name, "x".equalsIgnoreCase(cell(cells, PRIO)),
                        NO_UNIT.equals(unit) ? null : unit,
                        decimal(cell(cells, PACK), 3, "Opak.sprzedaż"),
                        decimal(cell(cells, FACTOR), 3, "Współcz. ceny"),
                        decimal(price, 2, "CenaSprzedaży"),
                        date(cell(cells, REMOVAL)),
                        deposit(cell(cells, DEPOSIT))));
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        private void readPreamble(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(ReportText.normalize(value), i);
                }
            }
            if (header.containsKey(ITM) && header.containsKey(EAN)) {
                columns = header;
                return;
            }
            for (String value : cells) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                Matcher store = STORE_NUMBER.matcher(ReportText.normalize(value));
                if (storeNumber == null && store.find()) {
                    storeNumber = store.group(1);
                    continue;
                }
                Matcher generated = GENERATED_AT.matcher(value);
                if (generatedAt == null && ReportText.normalize(value).startsWith("data") && generated.find()) {
                    try {
                        generatedAt = LocalDate.parse(generated.group(1), DATE)
                                .atTime(LocalTime.parse(generated.group(2), TIME));
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                }
            }
        }

        private ParsedProductCodeReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn Kod artykułu i Kod EAN13. "
                        + "Czy to raport multikodów (Szczegóły informacji kodu sprzedaży)?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (rows.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnych kodów");
            }
            if (declaredRows == null) {
                throw new InvalidReportException("W pliku nie ma ostatniego wiersza z liczbą linii (np. „Ilość linii: "
                        + ": 86430”). Plik może być niekompletny - wygeneruj go ponownie.");
            }
            if (declaredRows != fileRows) {
                throw new InvalidReportException("Raport deklaruje " + declaredRows + " linii, a w pliku jest "
                        + fileRows + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            return new ParsedProductCodeReport(storeNumber, generatedAt, List.copyOf(rows));
        }

        private static String itm8(String value) {
            if (value == null) {
                throw new IllegalArgumentException("brak kodu artykułu");
            }
            if (!DIGITS.matcher(value).matches() || value.length() > ITM8_LENGTH) {
                throw new IllegalArgumentException("Kod artykułu \"" + value + "\" musi mieć najwyżej " + ITM8_LENGTH
                        + " cyfr");
            }
            return "0".repeat(ITM8_LENGTH - value.length()) + value;
        }

        private static String ean(String value) {
            if (value == null) {
                throw new IllegalArgumentException("brak kodu EAN");
            }
            if (!DIGITS.matcher(value).matches() || value.length() > 14) {
                throw new IllegalArgumentException("EAN \"" + value + "\" musi mieć najwyżej 14 cyfr");
            }
            return value;
        }

        /** Liczba z komórki Excela ("0.707", czasem "4.5899999999999999") zaokrąglona do skali kolumny w bazie. */
        private static BigDecimal decimal(String value, int scale, String label) {
            if (value == null) {
                return null;
            }
            try {
                BigDecimal number = new BigDecimal(value.replace(',', '.').replaceAll("[\\s\\u00A0]", ""))
                        .setScale(scale, RoundingMode.HALF_UP);
                if (number.precision() - number.scale() > 9) {
                    throw new IllegalArgumentException(label + " \"" + value + "\" jest za duże");
                }
                return number;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " \"" + value + "\" nie jest liczbą");
            }
        }

        /** "14/10/2028" albo data zapisana przez Excela jako numer dnia. */
        private static LocalDate date(String value) {
            if (value == null) {
                return null;
            }
            try {
                if (DAY.matcher(value).matches()) {
                    return LocalDate.parse(value, DATE);
                }
                return EXCEL_EPOCH.plusDays(new BigDecimal(value).longValueExact());
            } catch (DateTimeParseException | NumberFormatException | ArithmeticException e) {
                throw new IllegalArgumentException("Data usunięcia \"" + value + "\" nie jest datą");
            }
        }

        private static Short deposit(String value) {
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value).shortValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                throw new IllegalArgumentException("Kaucja \"" + value + "\" nie jest numerem kaucji");
            }
        }

        private String cell(List<String> cells, String column) {
            Integer index = columns.get(column);
            String value = index == null || index >= cells.size() ? null : cells.get(index);
            return value == null || value.isBlank() ? null : value.trim();
        }

        private static boolean isBlank(List<String> cells) {
            return cells.stream().allMatch(value -> value == null || value.isBlank());
        }

        private static String firstNonBlank(List<String> cells) {
            return cells.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }
    }
}
