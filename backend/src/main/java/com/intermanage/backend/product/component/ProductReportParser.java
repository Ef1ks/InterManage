package com.intermanage.backend.product.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.product.dto.ParsedProductReport;
import com.intermanage.backend.product.dto.ProductReportRow;

/**
 * Czyta raport kartoteki produktów ("DANE DO IMPEKA", arkusz Mercalys). Układ pliku:
 * kilka wierszy nagłówka raportu (m.in. "Data : 24/10/2026 16:04:43"), wiersz z nazwami kolumn (EAN, NAZWA, ... CN),
 * po jednym wierszu na artykuł i na końcu "Ilość linii: :072346". Kolumny szukamy po nazwie, więc zmiana ich kolejności
 * w raporcie niczego nie psuje. Plik z jakimkolwiek błędnym wierszem jest odrzucany w całości.
 */
@Component
public class ProductReportParser {

    public static final int MAX_REPORTED_ERRORS = 20;

    private final XlsxReader xlsxReader;

    public ProductReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedProductReport parse(Path file) throws IOException, InvalidReportException {
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

        /** "TRYTOMA Sp. z o.o. - Numer punktu sprzedaży 10023" w pierwszym wierszu raportu. */
        private static final Pattern STORE_NUMBER = Pattern.compile("Numer punktu sprzeda\\S*\\s+(\\d+)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern REPORT_DATE =Pattern.compile("Data\\s*:\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
        private static final DateTimeFormatter REPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        private static final Pattern LINE_COUNT = Pattern.compile("Ilo\\S*\\s+linii\\D*(\\d+)", Pattern.CASE_INSENSITIVE);
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        /** Mieści się w najwęższej kolumnie liczbowej, numeric(12, 4). */
        private static final BigDecimal MAX_DECIMAL = new BigDecimal("100000000");

        private static final List<String> REQUIRED_COLUMNS = List.of(
                "EAN", "ITM8", "NAZWA", "CENA_SPRZEDAZY", "CENA_ZAKUPU", "STAN", "ROTACJA");

        private LocalDateTime generatedAt;
        private String storeNumber;
        private Map<String, Integer> columns;
        private Integer declaredLineCount;
        private final List<ProductReportRow> rows = new ArrayList<>();
        private final Set<String> seenItm8 = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readPreamble(cells);
                return;
            }
            if (isBlank(cells)) {
                return;
            }
            String first = firstNonBlank(cells);
            Matcher lineCount = LINE_COUNT.matcher(first);
            if (lineCount.find() && isBlank(cell(cells, "ITM8"))) {
                declaredLineCount = Integer.parseInt(lineCount.group(1));
                return;
            }
            try {
                ProductReportRow row = toProductRow(cells);
                if (!seenItm8.add(row.itm8())) {
                    throw new IllegalArgumentException("ITM8 " + row.itm8() + " występuje w pliku więcej niż raz");
                }
                rows.add(row);
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        /** Wiersze nad nagłówkiem: data wygenerowania raportu i sam nagłówek z nazwami kolumn. */
        private void readPreamble(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(value.trim().toUpperCase(), i);
                }
            }
            if (header.containsKey("EAN") && header.containsKey("ITM8")) {
                columns = header;
                return;
            }
            for (String value : cells) {
                if (value == null) {
                    continue;
                }
                Matcher store = STORE_NUMBER.matcher(value);
                if (storeNumber == null && store.find()) {
                    storeNumber = store.group(1);
                }
                Matcher date = REPORT_DATE.matcher(value);
                if (generatedAt == null && date.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(date.group(1).replaceAll("\\s+", " "), REPORT_DATE_FORMAT);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna - bez niej import i tak się uda.
                    }
                }
            }
        }

        private ParsedProductReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException(
                        "W pliku nie ma wiersza z nagłówkami kolumn (EAN, ITM8, NAZWA...). Czy to właściwy raport?");
            }
            List<String> missing = REQUIRED_COLUMNS.stream().filter(name -> !columns.containsKey(name)).toList();
            if (!missing.isEmpty()) {
                throw new InvalidReportException("Brakuje kolumn: " + String.join(", ", missing));
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors, errorCount);
            }
            if (rows.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnych produktów");
            }
            if (declaredLineCount != null && declaredLineCount != rows.size()) {
                throw new InvalidReportException("Raport deklaruje " + declaredLineCount + " linii, a w pliku jest "
                        + rows.size() + " produktów. Plik może być niekompletny - wygeneruj go ponownie.");
            }
            return new ParsedProductReport(storeNumber, generatedAt, List.copyOf(rows));
        }

        private ProductReportRow toProductRow(List<String> cells) {
            return new ProductReportRow(
                    code(cells, "ITM8", 13),
                    code(cells, "EAN", 14),
                    text(cells, "NAZWA", 255),
                    text(cells, "MARKA", 100),
                    text(cells, "DOST", 255),
                    text(cells, "NOM", 20),
                    decimal(cells, "CENA_SPRZEDAZY", 2),
                    decimal(cells, "CENA_ZAKUPU", 4),
                    decimal(cells, "STAN", 3),
                    decimal(cells, "ROTACJA", 3),
                    decimal(cells, "COLI", 3),
                    yesNo(cells, "AKTYWNY"),
                    yesNo(cells, "PRIO"),
                    smallInteger(cells, "VAT"),
                    text(cells, "JED", 10),
                    decimal(cells, "WAGA", 3),
                    integer(cells, "DLV", Integer.MAX_VALUE),
                    smallInteger(cells, "RP"),
                    smallInteger(cells, "KAUCJA"),
                    text(cells, "REFERENCJA", 20),
                    smallInteger(cells, "KASA"),
                    smallInteger(cells, "TYP"),
                    text(cells, "RODZ", 30),
                    smallInteger(cells, "JMSP"),
                    text(cells, "CN", 30),
                    decimal(cells, "CENA_CAD", 2));
        }

        /** Kod z samych cyfr (EAN, ITM8). Zera wiodące są częścią kodu. */
        private String code(List<String> cells, String column, int maxLength) {
            String value = text(cells, column, maxLength);
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + column);
            }
            if (!DIGITS.matcher(value).matches()) {
                throw new IllegalArgumentException(column + " \"" + value + "\" musi składać się z cyfr");
            }
            return value;
        }

        private String text(List<String> cells, String column, int maxLength) {
            String value = cell(cells, column);
            if (value == null || value.isBlank()) {
                return null;
            }
            value = value.trim();
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(column + " jest dłuższe niż " + maxLength + " znaków");
            }
            return value;
        }

        /** Excel zapisuje liczby binarnie (np. 0.27500000000000002), więc zaokrąglamy do dokładności kolumny. */
        private BigDecimal decimal(List<String> cells, String column, int scale) {
            String value = text(cells, column, 64);
            if (value == null) {
                return null;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value.replace(',', '.').replace(" ", "")).setScale(scale, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(column + " \"" + value + "\" nie jest liczbą");
            }
            if (number.abs().compareTo(MAX_DECIMAL) >= 0) {
                throw new IllegalArgumentException(column + " \"" + value + "\" jest poza dopuszczalnym zakresem");
            }
            return number;
        }

        private Integer integer(List<String> cells, String column, int max) {
            BigDecimal value = decimal(cells, column, 6);
            if (value == null) {
                return null;
            }
            if (value.stripTrailingZeros().scale() > 0 || value.abs().compareTo(BigDecimal.valueOf(max)) > 0) {
                throw new IllegalArgumentException(column + " \"" + value.stripTrailingZeros().toPlainString()
                        + "\" musi być liczbą całkowitą");
            }
            return value.intValueExact();
        }

        private Short smallInteger(List<String> cells, String column) {
            Integer value = integer(cells, column, Short.MAX_VALUE);
            return value == null ? null : value.shortValue();
        }

        private Boolean yesNo(List<String> cells, String column) {
            String value = text(cells, column, 10);
            if (value == null) {
                return null;
            }
            return switch (value.toLowerCase()) {
                case "tak", "t", "1", "true" -> true;
                case "nie", "n", "0", "false" -> false;
                default -> throw new IllegalArgumentException(column + " \"" + value + "\" - oczekiwano Tak lub Nie");
            };
        }

        /** Wartość kolumny po nazwie; {@code null}, gdy raport jej nie ma albo komórka jest pusta. */
        private String cell(List<String> cells, String column) {
            Integer index = columns.get(column);
            return index == null || index >= cells.size() ? null : cells.get(index);
        }

        private static boolean isBlank(List<String> cells) {
            return cells.stream().allMatch(ReportReader::isBlank);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String firstNonBlank(List<String> cells) {
            return cells.stream().filter(value -> !isBlank(value)).findFirst().orElse("");
        }
    }
}
