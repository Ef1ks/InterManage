package com.intermanage.backend.deliproduction.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.deliproduction.entity.ProcessParameters;
import com.intermanage.backend.product.component.ProductReportParser;

/**
 * Czyta bazę artykułów kart produkcji z arkusza "Baza" dawnego pliku Google (np. {@code Zeszyt1.xlsx}): wiersz
 * nagłówka z kolumnami Ean, Nazwa, Nomenklatura, Temperatura pieczenia, Czas schladzania, Temperatura schladzania,
 * Czas (ważność w dniach) i Wewnętrzna, potem po jednym artykule w wierszu. Kolumny pomocnicze (Czy pieczone, Drob,
 * Wiep, Inne) są pomijane: liczyły w arkuszu temperatury, które są już w swoich kolumnach. Jeden błędny wiersz
 * odrzuca cały plik.
 */
@Component
public class ProductionBaseParser {

    public record Row(String code, String name, String nomenclature, ProcessParameters parameters) {
    }

    private final XlsxReader xlsxReader;

    public ProductionBaseParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public List<Row> parse(Path file) throws IOException, InvalidReportException {
        FileReader reader = new FileReader();
        try {
            xlsxReader.readFirstSheet(file, reader::onRow);
        } catch (InvalidWorkbookException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku. Zapisz go w Excelu jako .xlsx.");
        }
        return reader.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony. */
    private static final class FileReader {

        private Map<String, Integer> columns;
        private final List<Row> rows = new ArrayList<>();
        private final Set<String> codes = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readHeader(cells);
                return;
            }
            String code = cell(cells, "ean");
            String name = cell(cells, "nazwa");
            if (code == null && name == null) {
                return;
            }
            try {
                if (name == null) {
                    throw new IllegalArgumentException("brak nazwy");
                }
                code = code(code);
                if (code != null && !codes.add(code)) {
                    throw new IllegalArgumentException("kod " + code + " jest w pliku drugi raz");
                }
                if (name.length() > 200) {
                    throw new IllegalArgumentException("nazwa dłuższa niż 200 znaków");
                }
                String nomenclature = cell(cells, "nomenklatura");
                rows.add(new Row(code, name, nomenclature != null && nomenclature.length() <= 20 ? nomenclature : null,
                        new ProcessParameters(
                                number(cell(cells, "temperatura pieczenia"), "temperatura pieczenia"),
                                number(cell(cells, "wewnetrzna"), "temperatura wewnętrzna"),
                                number(cell(cells, "czas schladzania"), "czas schładzania"),
                                number(cell(cells, "temperatura schladzania"), "temperatura schładzania"),
                                number(cell(cells, "czas"), "czas (ważność)"))));
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
            if (header.containsKey("ean") && header.containsKey("nazwa")) {
                columns = header;
            }
        }

        private List<Row> finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z kolumnami „Ean” i „Nazwa”");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zapisane.", errors,
                        errorCount);
            }
            if (rows.isEmpty()) {
                throw new InvalidReportException("Plik nie zawiera żadnego artykułu");
            }
            return rows;
        }

        private String cell(List<String> cells, String column) {
            Integer index = columns.get(column);
            String value = index != null && index < cells.size() ? cells.get(index) : null;
            return value == null || value.isBlank() ? null : value.trim();
        }

        /** Kod z Excela bywa liczbą ("2.41609E12", "5423552.0"): zapisujemy go cyframi, tak jak na etykiecie. */
        private static String code(String raw) {
            if (raw == null) {
                return null;
            }
            String code = raw.replace(" ", "");
            if (code.matches("[0-9.]+([eE][+-]?[0-9]+)?") && !code.matches("[0-9]+")) {
                try {
                    code = new BigDecimal(code).toBigIntegerExact().toString();
                } catch (ArithmeticException | NumberFormatException e) {
                    throw new IllegalArgumentException("„" + raw + "” to nie kod");
                }
            }
            if (!code.matches("[0-9A-Za-z]{1,20}")) {
                throw new IllegalArgumentException("„" + raw + "” to nie kod (cyfry albo litery, do 20 znaków)");
            }
            return code;
        }

        /** Liczba całkowita ("140", "140.0"); pusta komórka = brak parametru. */
        private static Integer number(String raw, String label) {
            if (raw == null) {
                return null;
            }
            try {
                return new BigDecimal(raw.replace(',', '.')).stripTrailingZeros().intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException(label + ": „" + raw + "” to nie liczba całkowita");
            }
        }
    }
}
