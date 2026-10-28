package com.intermanage.backend.pricecheck.component;

import java.io.IOException;
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

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.pricecheck.dto.ParsedCadencierReport;
import com.intermanage.backend.product.component.ProductReportParser;

/**
 * Czyta listę "OBECNE W CADENCIER" z systemu sklepu: "TRYTOMA Sp. z o.o. - Numer punktu sprzedaży 10023",
 * "Data : 28/10/2026 15:02:59", nazwa listy, wiersz nagłówka "ITM", "EAN", po jednym artykule w wierszu i na końcu
 * "Ilość linii: :013511". ITM to ITM8 z 13 cyframi (krótszy dopełniamy zerami, gdy Excel je zgubił). Ten sam ITM
 * podany drugi raz jest pomijany. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class CadencierReportParser {

    private final XlsxReader xlsxReader;

    public CadencierReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedCadencierReport parse(Path file) throws IOException, InvalidReportException {
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
        private static final Pattern GENERATED_AT = Pattern.compile(
                "Data\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{1,2}:\\d{2}:\\d{2})", Pattern.CASE_INSENSITIVE);
        /** "Ilość linii: :013511" po normalizacji. */
        private static final Pattern DECLARED_ROWS = Pattern.compile("^ilosc linii (\\d+)");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final int ITM8_LENGTH = 13;

        private static final String ITM = "itm";
        private static final String EAN = "ean";

        private String storeNumber;
        private LocalDateTime generatedAt;
        private Map<String, Integer> columns;
        private Integer declaredRows;
        private int fileRows;
        private final List<String> itm8s = new ArrayList<>();
        private final List<String> eans = new ArrayList<>();
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
                if (seen.add(itm8)) {
                    itm8s.add(itm8);
                    eans.add(ean);
                }
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
                if (generatedAt == null && generated.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                }
            }
        }

        private ParsedCadencierReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn ITM i EAN. "
                        + "Czy to lista OBECNE W CADENCIER?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (itm8s.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnych artykułów");
            }
            if (declaredRows != null && declaredRows != fileRows) {
                throw new InvalidReportException("Raport deklaruje " + declaredRows + " linii, a w pliku jest "
                        + fileRows + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            return new ParsedCadencierReport(storeNumber, generatedAt, List.copyOf(itm8s), eans);
        }

        private static String itm8(String value) {
            if (value == null) {
                throw new IllegalArgumentException("brak kodu ITM");
            }
            if (!DIGITS.matcher(value).matches() || value.length() > ITM8_LENGTH) {
                throw new IllegalArgumentException("ITM \"" + value + "\" musi mieć najwyżej " + ITM8_LENGTH + " cyfr");
            }
            return "0".repeat(ITM8_LENGTH - value.length()) + value;
        }

        private static String ean(String value) {
            if (value == null) {
                return null;
            }
            if (!DIGITS.matcher(value).matches() || value.length() > 14) {
                throw new IllegalArgumentException("EAN \"" + value + "\" musi mieć najwyżej 14 cyfr");
            }
            return value;
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
