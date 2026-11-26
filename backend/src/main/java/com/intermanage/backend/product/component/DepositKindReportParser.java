package com.intermanage.backend.product.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.PdfCells;
import com.intermanage.backend.common.component.PdfCells.Cell;
import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.product.dto.ParsedDepositKindReport;

/**
 * Czyta raport "LISTA OPAKOWAŃ ZWROTNYCH WG KODU" z systemu sklepu (PDF, jedna strona, np. ETATS_ARTICLE_AR_ETAT014):
 * data ("jeu. 26/11/2026 - 11:12:41"), "TRYTOMA Sp. z o.o. : 10023", kolumny Kod op.zwrotnych, Nazwa op.zwrotnych,
 * Kod VAT, Wartość EURO op.zwrotnych (to złote), Kod op.zwrotnych ("Kaucja") i stopka "10 linie".
 * <p>
 * Wiersz to napisy z {@link PdfCells} na jednej wysokości, czytany po kolei: kod, nazwa (bywa w niej kwota, np.
 * "BUTELKA 0,5 L 1,00 ZŁ"), kod VAT, wartość, rodzaj. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class DepositKindReportParser {

    /** Kod, nazwa, kod VAT, wartość z przecinkiem i opcjonalny rodzaj; nazwa bierze wszystko, co zostaje. */
    private static final Pattern ROW = Pattern
            .compile("^(\\d{1,3})\\s+(.+)\\s+(\\d{1,2})\\s+(\\d{1,4}(?:[ \\u00a0.]\\d{3})*,\\d{2})(?:\\s+(\\S.*))?$");
    private static final Pattern GENERATED_AT = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{1,2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
    /** "TRYTOMA Sp. z o.o. : 10023" po normalizacji. */
    private static final Pattern STORE_NUMBER = Pattern.compile("sp z o o (\\d{3,})$");
    /** "10 linie" po normalizacji. */
    private static final Pattern DECLARED_ROWS = Pattern.compile("^(\\d+) lini[ae]$");
    private static final int MAX_NAME = 100;
    private static final int MAX_REPORTED_ERRORS = 20;

    public ParsedDepositKindReport parse(Path file) throws IOException, InvalidReportException {
        if (!PdfCells.isPdf(file)) {
            throw new InvalidReportException(
                    "To nie jest plik PDF. Wgraj Listę opakowań zwrotnych wydrukowaną z systemu sklepu do PDF.");
        }
        ReportReader report = new ReportReader();
        try {
            PdfCells.read(file, (page, pageCount, cells) -> report.onPage(page, cells));
        } catch (IOException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku PDF. Sprawdź, czy nie jest uszkodzony.");
        } catch (RuntimeException e) {
            throw new InvalidReportException(
                    "Nie udało się odczytać tekstu z pliku PDF. Czy to Lista opakowań zwrotnych?");
        }
        return report.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        private String storeNumber;
        private LocalDateTime generatedAt;
        private boolean depositList;
        private Integer declaredRows;
        private final List<ParsedDepositKindReport.Row> rows = new ArrayList<>();
        private final Set<Short> seenCodes = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onPage(int page, List<Cell> cells) {
            Cell nameHeader = header(cells, "nazwa op");
            Cell vatHeader = header(cells, "kod vat");
            // Nagłówki kolumn stoją w jednym wierszu: wspólna współrzędna to wysokość wiersza (strona bywa obrócona).
            boolean rowIsA = nameHeader == null || vatHeader == null
                    || Math.abs(nameHeader.a() - vatHeader.a()) <= Math.abs(nameHeader.b() - vatHeader.b());
            Map<Integer, List<Cell>> lines = new TreeMap<>();
            for (Cell cell : cells) {
                lines.computeIfAbsent(Math.round(rowIsA ? cell.a() : cell.b()), key -> new ArrayList<>()).add(cell);
            }
            for (List<Cell> line : lines.values()) {
                line.sort(Comparator.comparingDouble(cell -> rowIsA ? cell.b() : cell.a()));
                onLine(page, line.stream().map(Cell::text).collect(Collectors.joining(" ")).replaceAll("\\s+", " ")
                        .strip());
            }
        }

        private static Cell header(List<Cell> cells, String start) {
            return cells.stream().filter(cell -> ReportText.normalize(cell.text()).startsWith(start)).findFirst()
                    .orElse(null);
        }

        private void onLine(int page, String text) {
            String normalized = ReportText.normalize(text);
            if (normalized.contains("lista opakowan zwrotnych")) {
                depositList = true;
                return;
            }
            Matcher declared = DECLARED_ROWS.matcher(normalized);
            if (declared.matches()) {
                declaredRows = Integer.parseInt(declared.group(1));
                return;
            }
            Matcher store = STORE_NUMBER.matcher(normalized);
            if (storeNumber == null && store.find()) {
                storeNumber = store.group(1);
                return;
            }
            Matcher generated = GENERATED_AT.matcher(text);
            if (generatedAt == null && generated.find()) {
                try {
                    generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                } catch (DateTimeParseException ignored) {
                    // Data jest tylko informacyjna.
                }
                return;
            }
            if (text.isEmpty() || !Character.isDigit(text.charAt(0))) {
                return;
            }
            Matcher row = ROW.matcher(text);
            try {
                if (!row.matches()) {
                    throw new IllegalArgumentException("wiersz nie ma kodu, nazwy, kodu VAT i wartości: \"" + text + "\"");
                }
                short code = Short.parseShort(row.group(1));
                if (!seenCodes.add(code)) {
                    throw new IllegalArgumentException("kod " + code + " występuje w pliku więcej niż raz");
                }
                String name = row.group(2).strip();
                if (name.length() > MAX_NAME) {
                    throw new IllegalArgumentException("nazwa jest dłuższa niż " + MAX_NAME + " znaków");
                }
                BigDecimal value = new BigDecimal(row.group(4).replaceAll("[ \\u00a0.]", "").replace(',', '.'));
                String kind = row.group(5) == null ? null : row.group(5).strip();
                rows.add(new ParsedDepositKindReport.Row(code, name, Short.valueOf(row.group(3)), value,
                        kind == null || kind.isEmpty() ? null : kind.length() > 50 ? kind.substring(0, 50) : kind));
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < MAX_REPORTED_ERRORS) {
                    errors.add("Strona " + page + ": " + e.getMessage());
                }
            }
        }

        private ParsedDepositKindReport finish() throws InvalidReportException {
            if (!depositList || rows.isEmpty() && errorCount == 0) {
                throw new InvalidReportException("W pliku nie ma listy opakowań zwrotnych (Kod op.zwrotnych, Nazwa "
                        + "op.zwrotnych, Kod VAT, Wartość). Czy to Lista opakowań zwrotnych?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (storeNumber == null) {
                throw new InvalidReportException("W pliku nie ma numeru sklepu (np. „TRYTOMA Sp. z o.o. : 10023”). "
                        + "Wydrukuj listę ponownie z systemu sklepu.");
            }
            if (declaredRows == null) {
                throw new InvalidReportException("W pliku nie ma liczby linii (np. „10 linie”). "
                        + "Plik może być niekompletny - wydrukuj go ponownie.");
            }
            if (declaredRows != rows.size()) {
                throw new InvalidReportException("Lista deklaruje " + declaredRows + " linii, a w pliku jest "
                        + rows.size() + ". Plik może być niekompletny - wydrukuj go ponownie.");
            }
            List<ParsedDepositKindReport.Row> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparingInt(ParsedDepositKindReport.Row::code));
            return new ParsedDepositKindReport(storeNumber, generatedAt, List.copyOf(sorted));
        }
    }
}
