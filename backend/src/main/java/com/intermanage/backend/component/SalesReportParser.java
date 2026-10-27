package com.intermanage.backend.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.dto.SalesReportBatch;
import com.intermanage.backend.dto.SalesReportHeader;
import com.intermanage.backend.exception.InvalidReportException;
import com.intermanage.backend.exception.InvalidWorkbookException;

/**
 * Czyta raport sprzedaży "Zestawienie: Wejścia/Wyjścia" (wg artykułu, wg dnia, sprzedaż po rabacie, FIFO).
 * Układ pliku: "PDV: 10023 - TRYTOMA Sp. z o.o. Data: 26/10/2026 Godzina: 15:03:27", opis zestawienia,
 * "Wybór Danych: Od 31/12/2025 do 30/06/2026", wiersz z nazwami kolumn (ITM8 Prio ... % Marża) i po jednym
 * wierszu na artykuł, EAN i dzień. Plików jest do ok. 500 tys. wierszy, więc parser niczego nie zbiera: oddaje nagłówek,
 * a potem paczki wierszy, które wywołujący od razu zapisuje (w transakcji, wycofywanej, gdy plik okaże się błędny).
 */
@Component
public class SalesReportParser {

    /** Odbiorca danych z pliku. Nagłówek przychodzi raz, przed pierwszą paczką. */
    public interface Sink {
        void header(SalesReportHeader header);

        void batch(SalesReportBatch batch);
    }

    /** Podsumowanie całego pliku; wołane dopiero, gdy wszystkie wiersze są poprawne. */
    public record Summary(int totalRows) {
    }

    private final XlsxReader xlsxReader;

    public SalesReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public Summary parse(Path file, int batchSize, Sink sink) throws IOException, InvalidReportException {
        ReportReader reader = new ReportReader(batchSize, sink);
        try {
            xlsxReader.readFirstSheet(file, reader::onRow);
        } catch (InvalidWorkbookException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku. Wgraj raport zapisany jako .xlsx lub .xlsm.");
        } catch (ReportFormatException e) {
            throw new InvalidReportException(e.getMessage());
        }
        return reader.finish();
    }

    /**
     * Klucz wiersza w pliku bez napisów (plików jest do ok. 500 tys. wierszy): ITM8 i dzień jako jedna liczba (ITM8 ma
     * do 13 cyfr, dzień od 1970 to mniej niż 100 000) i EAN jako liczba z długością na końcu (zera wiodące są
     * częścią kodu), -1 dla pustego.
     */
    private record RowKey(long itm8Day, long ean) {
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        private static final Pattern STORE_NUMBER = Pattern.compile("PDV\\s*:\\s*(\\d{3,})", Pattern.CASE_INSENSITIVE);
        private static final Pattern GENERATED_AT = Pattern.compile(
                "Data\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+Godzina\\s*:\\s*(\\d{1,2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
        private static final Pattern PERIOD = Pattern.compile(
                "Od\\s+(\\d{2}/\\d{2}/\\d{4})\\s+do\\s+(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE);
        /**
         * Ostatni wiersz raportu: "Ilość linii:650364" w kolumnie ITM8. Cała komórka, bo nazwy produktów też mają
         * "96 LINIA" itp.
         */
        private static final Pattern DECLARED_LINES = Pattern.compile(
                "\\s*(?:ilo[sś][cć]\\s+linii|liczba\\s+wierszy)\\s*:?\\s*(\\d+)\\s*",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        /** numeric(14, 2) w bazie. */
        private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
        /** numeric(9, 2) w bazie. */
        private static final BigDecimal MAX_PERCENT = new BigDecimal("10000000");

        private static final String ITM8 = "itm8 prio";
        private static final String EAN = "ean prio";
        private static final String DATE_COLUMN = "data";
        private static final String QUANTITY = "ilosc";
        private static final String COST = "wartosc fifo";
        private static final String SALE_GROSS = "wartosc ceny sprzedazy";
        private static final String VAT = "wartosc vat";
        private static final String MARGIN = "wart marza fifo";
        private static final String MARGIN_PERCENT = "marza";
        private static final List<String> REQUIRED_COLUMNS = List.of(ITM8, DATE_COLUMN, QUANTITY, COST, SALE_GROSS, VAT, MARGIN);
        /** Nazwy kolumn do komunikatów o błędach, tak jak w raporcie. */
        private static final Map<String, String> LABELS = Map.of(
                ITM8, "ITM8 Prio", EAN, "EAN Prio", DATE_COLUMN, "Data", QUANTITY, "Ilość", COST, "Wartosc FIFO",
                SALE_GROSS, "Wartość ceny sprzedaży", VAT, "Wartość VAT", MARGIN, "Wart.Marza FIFO",
                MARGIN_PERCENT, "% Marża");

        private final int batchSize;
        private final Sink sink;

        private String storeNumber;
        private LocalDateTime generatedAt;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private Map<String, Integer> columns;
        private int[] index;
        private Integer declaredLines;
        private boolean footerReached;
        private int totalRows;
        private final Set<RowKey> seenKeys = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private int filled;
        private LocalDate[] dates;
        private String[] itm8s;
        private String[] eans;
        private BigDecimal[] quantities;
        private BigDecimal[] costValues;
        private BigDecimal[] saleValuesGross;
        private BigDecimal[] vatValues;
        private BigDecimal[] marginValues;
        private BigDecimal[] marginPercents;

        ReportReader(int batchSize, Sink sink) {
            this.batchSize = batchSize;
            this.sink = sink;
            newBatch();
        }

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readPreamble(cells);
                return;
            }
            if (isBlank(cells)) {
                return;
            }
            // Wiersz z liczbą wierszy kończy raport, nawet gdy tekst stoi w kolumnie ITM8.
            Integer lines = declaredLines(cells);
            if (lines != null) {
                footerReached = true;
                declaredLines = lines;
                return;
            }
            String itm8 = cell(cells, ITM8);
            if (itm8 == null || itm8.isBlank()) {
                // Inne wiersze stopki (np. suma): dalej nie powinno już być wierszy z artykułami.
                footerReached = true;
                return;
            }
            totalRows++;
            try {
                if (footerReached) {
                    throw new IllegalArgumentException("wiersz z artykułem pod stopką raportu");
                }
                addRow(cells);
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        private static Integer declaredLines(List<String> cells) {
            for (String value : cells) {
                Matcher lines = value == null ? null : DECLARED_LINES.matcher(value);
                if (lines != null && lines.matches()) {
                    return Integer.parseInt(lines.group(1));
                }
            }
            return null;
        }

        /** Wiersze nad nagłówkiem kolumn: numer sklepu, data wygenerowania, zakres dat i sam nagłówek. */
        private void readPreamble(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(DocumentReportParser.normalize(value), i);
                }
            }
            if (header.containsKey(ITM8) && header.containsKey(DATE_COLUMN)) {
                startRows(header);
                return;
            }
            for (String value : cells) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                Matcher store = STORE_NUMBER.matcher(value);
                if (storeNumber == null && store.find()) {
                    storeNumber = store.group(1);
                }
                Matcher generated = GENERATED_AT.matcher(value);
                if (generatedAt == null && generated.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                }
                Matcher period = PERIOD.matcher(value);
                if (periodFrom == null && period.find()) {
                    try {
                        periodFrom = LocalDate.parse(period.group(1), DATE);
                        periodTo = LocalDate.parse(period.group(2), DATE);
                    } catch (DateTimeParseException ignored) {
                        periodFrom = null;
                        periodTo = null;
                    }
                }
            }
        }

        /** Nagłówek kolumn znaleziony: sprawdzamy go i przekazujemy dane z nagłówka raportu, zanim przyjdą wiersze. */
        private void startRows(Map<String, Integer> header) {
            List<String> missing = REQUIRED_COLUMNS.stream().filter(name -> !header.containsKey(name))
                    .map(LABELS::get).toList();
            if (!missing.isEmpty()) {
                throw new ReportFormatException("Brakuje kolumn: " + String.join(", ", missing));
            }
            if (periodFrom == null || periodFrom.isAfter(periodTo)) {
                throw new ReportFormatException("W nagłówku raportu nie ma zakresu dat (\"Wybór Danych: Od … do …\"). "
                        + "Czy to raport sprzedaży wg artykułu i dnia?");
            }
            columns = header;
            index = new int[] {header.get(ITM8), header.getOrDefault(EAN, -1), header.get(DATE_COLUMN),
                    header.get(QUANTITY), header.get(COST), header.get(SALE_GROSS), header.get(VAT), header.get(MARGIN),
                    header.getOrDefault(MARGIN_PERCENT, -1)};
            sink.header(new SalesReportHeader(storeNumber, generatedAt, periodFrom, periodTo));
        }

        private void addRow(List<String> cells) {
            String itm8 = code(cells.get(index[0]), ITM8, 13);
            String ean = index[1] < 0 || index[1] >= cells.size() ? null : code(cells.get(index[1]), EAN, 14);
            LocalDate date = date(at(cells, 2));
            if (date.isBefore(periodFrom) || date.isAfter(periodTo)) {
                throw new IllegalArgumentException("data " + DATE.format(date) + " jest poza okresem raportu ("
                        + DATE.format(periodFrom) + " - " + DATE.format(periodTo) + ")");
            }
            if (!seenKeys.add(new RowKey(Long.parseLong(itm8) * 100_000 + date.toEpochDay(),
                    ean == null ? -1 : Long.parseLong(ean) * 100 + ean.length()))) {
                throw new IllegalArgumentException("ITM8 " + itm8 + (ean == null ? " bez EAN" : " z EAN " + ean)
                        + " z dnia " + DATE.format(date) + " występuje w pliku więcej niż raz");
            }
            BigDecimal quantity = required(decimal(at(cells, 3), QUANTITY, 3, MAX_AMOUNT), QUANTITY);
            BigDecimal cost = required(decimal(at(cells, 4), COST, 2, MAX_AMOUNT), COST);
            BigDecimal saleGross = required(decimal(at(cells, 5), SALE_GROSS, 2, MAX_AMOUNT), SALE_GROSS);
            BigDecimal vat = required(decimal(at(cells, 6), VAT, 2, MAX_AMOUNT), VAT);
            BigDecimal margin = required(decimal(at(cells, 7), MARGIN, 2, MAX_AMOUNT), MARGIN);
            BigDecimal marginPercent = decimal(at(cells, 8), MARGIN_PERCENT, 2, MAX_PERCENT);
            if (errorCount > 0) {
                // Plik i tak zostanie odrzucony; dalej tylko zbieramy błędy.
                return;
            }
            dates[filled] = date;
            itm8s[filled] = itm8;
            eans[filled] = ean;
            quantities[filled] = quantity;
            costValues[filled] = cost;
            saleValuesGross[filled] = saleGross;
            vatValues[filled] = vat;
            marginValues[filled] = margin;
            marginPercents[filled] = marginPercent;
            if (++filled == batchSize) {
                flush();
            }
        }

        private SalesReportParser.Summary finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn (ITM8 Prio, Data, Ilość...). "
                        + "Czy to raport sprzedaży wg artykułu i dnia?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors, errorCount);
            }
            if (totalRows == 0) {
                throw new InvalidReportException("Plik nie zawiera żadnych wierszy sprzedaży");
            }
            if (declaredLines == null) {
                throw new InvalidReportException("Na końcu pliku nie ma wiersza z liczbą wierszy (\"Ilość linii: …\"). "
                        + "Plik może być niekompletny - wygeneruj go ponownie.");
            }
            // Jak w innych raportach systemu sklepu: liczba linii może obejmować wiersz z nazwami kolumn.
            if (declaredLines != totalRows && declaredLines != totalRows + 1) {
                throw new InvalidReportException("Raport deklaruje " + declaredLines + " linii, a w pliku jest "
                        + totalRows + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            if (filled > 0) {
                flush();
            }
            return new SalesReportParser.Summary(totalRows);
        }

        /** Pełna paczka idzie dalej bez kopiowania (parser zaczyna nowe tablice), ostatnia jest przycinana. */
        private void flush() {
            if (filled == batchSize) {
                sink.batch(new SalesReportBatch(dates, itm8s, eans, quantities, costValues, saleValuesGross, vatValues,
                        marginValues, marginPercents));
                newBatch();
            } else {
                sink.batch(new SalesReportBatch(Arrays.copyOf(dates, filled), Arrays.copyOf(itm8s, filled),
                        Arrays.copyOf(eans, filled), Arrays.copyOf(quantities, filled), Arrays.copyOf(costValues, filled),
                        Arrays.copyOf(saleValuesGross, filled), Arrays.copyOf(vatValues, filled),
                        Arrays.copyOf(marginValues, filled), Arrays.copyOf(marginPercents, filled)));
            }
            filled = 0;
        }

        private void newBatch() {
            dates = new LocalDate[batchSize];
            itm8s = new String[batchSize];
            eans = new String[batchSize];
            quantities = new BigDecimal[batchSize];
            costValues = new BigDecimal[batchSize];
            saleValuesGross = new BigDecimal[batchSize];
            vatValues = new BigDecimal[batchSize];
            marginValues = new BigDecimal[batchSize];
            marginPercents = new BigDecimal[batchSize];
        }

        private String at(List<String> cells, int column) {
            int i = index[column];
            return i < 0 || i >= cells.size() ? null : cells.get(i);
        }

        /** "31/12/2025" jako tekst albo data Excela zapisana liczbą dni od 1899-12-30. */
        private static LocalDate date(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("brak daty sprzedaży (Data)");
            }
            String trimmed = value.trim();
            try {
                if (trimmed.matches("\\d+(\\.0+)?")) {
                    return LocalDate.of(1899, 12, 30).plusDays(Long.parseLong(trimmed.replaceAll("\\..*", "")));
                }
                return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed, DATE);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("data \"" + value + "\" nie jest w formacie DD/MM/RRRR");
            }
        }

        /** Kod z samych cyfr (EAN, ITM8). Zera wiodące są częścią kodu. */
        private static String code(String value, String column, int maxLength) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.trim();
            if (!DIGITS.matcher(trimmed).matches() || trimmed.length() > maxLength) {
                throw new IllegalArgumentException(LABELS.get(column) + " \"" + trimmed + "\" musi mieć do "
                        + maxLength + " cyfr");
            }
            return trimmed;
        }

        /** Excel zapisuje liczby binarnie (np. 0.28000000000000003), więc zaokrąglamy do dokładności kolumny. */
        private static BigDecimal decimal(String value, String column, int scale, BigDecimal max) {
            if (value == null || value.isBlank()) {
                return null;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value.trim().replace("%", "").replace(',', '.').replace(" ", ""))
                        .setScale(scale, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(LABELS.get(column) + " \"" + value + "\" nie jest liczbą");
            }
            if (number.abs().compareTo(max) >= 0) {
                throw new IllegalArgumentException(LABELS.get(column) + " \"" + value + "\" jest poza dopuszczalnym zakresem");
            }
            return number;
        }

        private static BigDecimal required(BigDecimal value, String column) {
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + LABELS.get(column));
            }
            return value;
        }

        private String cell(List<String> cells, String column) {
            Integer i = columns.get(column);
            return i == null || i >= cells.size() ? null : cells.get(i);
        }

        private static boolean isBlank(List<String> cells) {
            return cells.stream().allMatch(value -> value == null || value.isBlank());
        }
    }

    /** Błąd całego pliku wykryty w trakcie czytania (np. brak kolumn); {@link #parse} zamienia go na wyjątek raportu. */
    private static final class ReportFormatException extends RuntimeException {
        ReportFormatException(String message) {
            super(message);
        }
    }
}
