package com.intermanage.backend.cadtop.component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;

/**
 * Plan cadencier z intranetu (np. {@code cad_planing_2027_pdv.xlsx}): tabela nr_cad / data poczatku / data konca /
 * komentarz w pierwszym arkuszu, który ją ma (2026: „Plan Cadencier” za kalendarzem, 2027: „planing cad” na początku,
 * z wierszem tytułu). Kalendarza z kolorami nie czytamy, bo w 2027 nie zgadza się z tabelą. Błędny wiersz odrzuca plik.
 */
@Component
public class CadPlanParser {

    private static final int HEADER_ROWS = 10;
    private static final int MAX_REPORTED_ERRORS = 20;
    private static final DateTimeFormatter POLISH_DATE = DateTimeFormatter.ofPattern("d.MM.yyyy");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** {@code year}: rok, w którym zaczyna się najwięcej numerów; {@code modifiedAt}: zapis skoroszytu, może być pusty. */
    public record Parsed(int year, Instant modifiedAt, List<Entry> entries) {
    }

    /** {@code nrCad} pusty = „zmiany ręczne”. */
    public record Entry(int lp, String nrCad, LocalDate startsOn, LocalDate endsOn, String comment) {
    }

    private record Columns(int row, int number, int from, int to, int comment) {
    }

    private final XlsxReader xlsxReader;

    public CadPlanParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public Parsed parse(Path file) throws IOException, InvalidWorkbookException, InvalidReportException {
        for (String sheet : xlsxReader.sheetNames(file)) {
            List<List<String>> rows = new ArrayList<>();
            xlsxReader.readSheet(file, sheet, (rowNumber, cells) -> {
                while (rows.size() < rowNumber - 1) {
                    rows.add(List.of());
                }
                rows.add(cells);
            });
            Columns columns = columns(rows);
            if (columns != null) {
                List<Entry> entries = entries(rows, columns);
                return new Parsed(year(entries), xlsxReader.modifiedAt(file), entries);
            }
        }
        throw new InvalidReportException(
                "To nie jest plan cadencier: brak tabeli z kolumnami nr_cad i data poczatku");
    }

    private static Columns columns(List<List<String>> rows) {
        for (int r = 0; r < Math.min(HEADER_ROWS, rows.size()); r++) {
            List<String> keys = rows.get(r).stream().map(CadSheets::key).toList();
            int number = keys.indexOf("nrcad");
            int from = keys.indexOf("datapoczatku");
            if (number >= 0 && from >= 0) {
                return new Columns(r, number, from, keys.indexOf("datakonca"), keys.indexOf("komentarz"));
            }
        }
        return null;
    }

    private static List<Entry> entries(List<List<String>> rows, Columns columns) throws InvalidReportException {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        for (int r = columns.row() + 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            String number = CadSheets.text(cells, columns.number());
            String from = CadSheets.text(cells, columns.from());
            if (number == null && from == null) {
                continue;
            }
            try {
                if (from == null) {
                    throw new IllegalArgumentException("brak daty początku");
                }
                String to = CadSheets.text(cells, columns.to());
                entries.add(new Entry(entries.size() + 1, number == null ? null : CadSheets.cadNumber(number),
                        date(from), to == null ? null : date(to),
                        CadSheets.shorten(CadSheets.text(cells, columns.comment()), 300)));
            } catch (IllegalArgumentException e) {
                if (++errorCount <= MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        if (errorCount > 0) {
            throw new InvalidReportException("Plan ma błędne wiersze", errors, errorCount);
        }
        if (entries.stream().allMatch(entry -> entry.nrCad() == null)) {
            throw new InvalidReportException("Plan nie ma żadnego numeru cadencier");
        }
        return entries;
    }

    private static int year(List<Entry> entries) {
        Map<Integer, Integer> counts = new TreeMap<>();
        entries.forEach(entry -> counts.merge(entry.startsOn().getYear(), 1, Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    private static LocalDate date(String value) {
        if (value.matches("\\d{1,6}(\\.\\d+)?")) {
            return EXCEL_EPOCH.plusDays(Long.parseLong(value.replaceAll("\\..*", "")));
        }
        try {
            return value.contains(".") ? LocalDate.parse(value, POLISH_DATE) : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("„" + value + "” to nie data");
        }
    }
}
