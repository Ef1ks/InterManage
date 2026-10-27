package com.intermanage.backend.supplier.component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.supplier.dto.ParsedSupplierReport;
import com.intermanage.backend.supplier.dto.SupplierReportRow;

/**
 * Czyta raport "Lista dostawców" z systemu sklepu. Układ pliku: "TRYTOMA Sp. z o.o. - Numer punktu sprzedaży 10023",
 * "Data : 25/10/2026 12:46:21", "LISTA DOSTAWCÓW", wiersz z nazwami kolumn (Kod dost. ... Ilość ITM), po jednym
 * wierszu na dostawcę i na końcu "Ilość linii: 317". Kolumny szukamy po nazwie (bez znaków diakrytycznych),
 * zapisujemy tylko dane potrzebne w aplikacji. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class SupplierReportParser {

    private final XlsxReader xlsxReader;

    public SupplierReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedSupplierReport parse(Path file) throws IOException, InvalidReportException {
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

        /** "... - Numer punktu sprzedaży 10023" po normalizacji. */
        private static final Pattern STORE_NUMBER = Pattern.compile("numer punktu sprzedazy (\\d{3,})");
        /** "Data : 25/10/2026  12:46:21". */
        private static final Pattern GENERATED_AT = Pattern.compile(
                "Data\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{1,2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
        /** "Ilość linii:  317" po normalizacji. */
        private static final Pattern DECLARED_ROWS = Pattern.compile("^ilosc linii (\\d+)");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        private static final Pattern DIGITS = Pattern.compile("\\d+");

        private static final String CODE = "kod dost";
        private static final String NAME = "nazwa firmy 1";
        private static final List<String> REQUIRED_COLUMNS = List.of(CODE, NAME);
        private static final Map<String, String> LABELS = Map.of(CODE, "Kod dost.", NAME, "Nazwa firmy 1");

        private String storeNumber;
        private LocalDateTime generatedAt;
        private Map<String, Integer> columns;
        private Integer declaredRows;
        private final List<SupplierReportRow> rows = new ArrayList<>();
        private final Set<String> seenCodes = new HashSet<>();
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
            try {
                SupplierReportRow row = toRow(cells);
                if (!seenCodes.add(row.code())) {
                    throw new IllegalArgumentException("dostawca o kodzie " + row.code() + " występuje w pliku więcej niż raz");
                }
                rows.add(row);
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        /** Wiersze nad nagłówkiem: numer sklepu, data wygenerowania i sam nagłówek. */
        private void readPreamble(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(ReportText.normalize(value), i);
                }
            }
            if (header.containsKey(CODE) && header.containsKey(NAME)) {
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
                if (generatedAt == null && generated.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                }
            }
        }

        private ParsedSupplierReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn (Kod dost., Nazwa firmy 1...). "
                        + "Czy to raport Lista dostawców?");
            }
            List<String> missing = REQUIRED_COLUMNS.stream().filter(name -> !columns.containsKey(name))
                    .map(LABELS::get).toList();
            if (!missing.isEmpty()) {
                throw new InvalidReportException("Brakuje kolumn: " + String.join(", ", missing));
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors, errorCount);
            }
            if (rows.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnych dostawców");
            }
            if (declaredRows != null && declaredRows != rows.size()) {
                throw new InvalidReportException("Raport deklaruje " + declaredRows + " dostawców, a w pliku jest "
                        + rows.size() + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            return new ParsedSupplierReport(storeNumber, generatedAt, List.copyOf(rows));
        }

        private SupplierReportRow toRow(List<String> cells) {
            String code = text(cells, CODE, 10);
            if (code == null) {
                throw new IllegalArgumentException("brak kodu dostawcy (Kod dost.)");
            }
            if (!DIGITS.matcher(code).matches()) {
                throw new IllegalArgumentException("kod dostawcy \"" + code + "\" musi składać się z cyfr");
            }
            String name = joined(200, text(cells, NAME, 200), text(cells, "nazwa firmy 2", 200));
            if (name == null) {
                throw new IllegalArgumentException("dostawca " + code + " nie ma nazwy (Nazwa firmy 1)");
            }
            return new SupplierReportRow(
                    code,
                    text(cells, "slowo klucz", 30),
                    name,
                    text(cells, "kod nip", 20),
                    joined(255, text(cells, "adres 1", 255), text(cells, "adres 2", 255)),
                    text(cells, "kod pocztowy", 10),
                    text(cells, "adres dostawcy", 100),
                    text(cells, "pochodz", 30));
        }

        /** Dwie kolumny jednej wartości ("Nazwa firmy 1" i "2"), ze spacją; za długie ucinamy do pojemności kolumny. */
        private static String joined(int maxLength, String... parts) {
            String value = Stream.of(parts).filter(part -> part != null).collect(Collectors.joining(" "));
            if (value.isEmpty()) {
                return null;
            }
            return value.length() > maxLength ? value.substring(0, maxLength).trim() : value;
        }

        private String text(List<String> cells, String column, int maxLength) {
            Integer index = columns.get(column);
            String value = index == null || index >= cells.size() ? null : cells.get(index);
            if (value == null || value.isBlank()) {
                return null;
            }
            value = value.trim().replaceAll("\\s+", " ");
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(LABELS.getOrDefault(column, column) + " jest dłuższe niż "
                        + maxLength + " znaków");
            }
            return value;
        }

        private static boolean isBlank(List<String> cells) {
            return cells.stream().allMatch(value -> value == null || value.isBlank());
        }

        private static String firstNonBlank(List<String> cells) {
            return cells.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("")
                    .toLowerCase(Locale.ROOT);
        }
    }
}
