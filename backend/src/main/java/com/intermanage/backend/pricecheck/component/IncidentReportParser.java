package com.intermanage.backend.pricecheck.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.pricecheck.dto.ParsedIncidentReport;
import com.intermanage.backend.pricecheck.dto.ParsedIncidentReport.Item;
import com.intermanage.backend.product.component.ProductReportParser;

/**
 * Czyta plik incydentów zamówień do bazy ("10023_261028_261028_W_NS_INCYDENTY.xlsx"): wiersz nagłówka (Baza, Sklep,
 * Nazwa Sklepu, Przyczyna, Komentarz, ITM8, Marka, Nazwa, EAN, Pakow, Zamówiono, Dostarczono, ITM8 Odpowiednika,
 * Kod Incydentu, Czy Zamawiał) i po jednym artykule w wierszu. ITM8 ma w pliku 8 cyfr, dopełniamy go zerami do 13
 * jak w raportach sklepu. Ten sam ITM8 drugi raz jest pomijany. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class IncidentReportParser {

    private final XlsxReader xlsxReader;

    public IncidentReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedIncidentReport parse(Path file) throws IOException, InvalidReportException {
        ReportReader reader = new ReportReader();
        try {
            xlsxReader.readFirstSheet(file, reader::onRow);
        } catch (InvalidWorkbookException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku. Wgraj plik zapisany jako .xlsx lub .xlsm.");
        }
        return reader.finish();
    }

    private static final class ReportReader {

        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final int ITM8_LENGTH = 13;
        private static final int TEXT_LENGTH = 255;

        private static final String STORE = "sklep";
        private static final String ITM8 = "itm8";
        private static final String REASON = "przyczyna";
        private static final String COMMENT = "komentarz";
        private static final String BRAND = "marka";
        private static final String NAME = "nazwa";
        private static final String EAN = "ean";
        private static final String ORDERED = "zamowiono";
        private static final String DELIVERED = "dostarczono";
        private static final String CODE = "kod incydentu";

        private Map<String, Integer> columns;
        private final Set<String> storeNumbers = new HashSet<>();
        private final List<Item> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readHeader(cells);
                return;
            }
            if (cells.stream().allMatch(value -> value == null || value.isBlank())) {
                return;
            }
            try {
                String itm8 = itm8(cell(cells, ITM8));
                String store = cell(cells, STORE);
                if (store != null) {
                    storeNumbers.add(store);
                }
                if (seen.add(itm8)) {
                    items.add(new Item(itm8, ean(cell(cells, EAN)), text(cell(cells, NAME)),
                            text(cell(cells, BRAND)), cell(cells, REASON), cell(cells, COMMENT),
                            text(cell(cells, CODE)), quantity(cell(cells, ORDERED)),
                            quantity(cell(cells, DELIVERED))));
                }
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        private void readHeader(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(ReportText.normalize(value), i);
                }
            }
            if (header.containsKey(ITM8) && header.containsKey(REASON)) {
                columns = header;
            }
        }

        private ParsedIncidentReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn ITM8 i Przyczyna. "
                        + "Czy to plik incydentów?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (items.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnych artykułów");
            }
            return new ParsedIncidentReport(Set.copyOf(storeNumbers), List.copyOf(items));
        }

        private static String itm8(String value) {
            if (value == null) {
                throw new IllegalArgumentException("brak kodu ITM8");
            }
            if (!DIGITS.matcher(value).matches() || value.length() > ITM8_LENGTH) {
                throw new IllegalArgumentException("ITM8 \"" + value + "\" musi mieć najwyżej " + ITM8_LENGTH + " cyfr");
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

        private static BigDecimal quantity(String value) {
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value.replace(',', '.').replace(" ", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ilość \"" + value + "\" nie jest liczbą");
            }
        }

        private static String text(String value) {
            return value == null || value.length() <= TEXT_LENGTH ? value : value.substring(0, TEXT_LENGTH);
        }

        private String cell(List<String> cells, String column) {
            Integer index = columns.get(column);
            String value = index == null || index >= cells.size() ? null : cells.get(index);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
