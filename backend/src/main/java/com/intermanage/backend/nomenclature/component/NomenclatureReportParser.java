package com.intermanage.backend.nomenclature.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.nomenclature.dto.NomenclatureReportRow;
import com.intermanage.backend.nomenclature.dto.ParsedNomenclatureReport;
import com.intermanage.backend.product.component.ProductReportParser;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.FinalText;
import com.lowagie.text.pdf.parser.ParsedText;
import com.lowagie.text.pdf.parser.ParsedTextImpl;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.pdf.parser.TextAssembler;
import com.lowagie.text.pdf.parser.Word;

/**
 * Czyta raport "LISTA NOMENKLATUR" z systemu sklepu (PDF, np. nomenklatury.pdf, ok. 50 stron). Na każdej stronie:
 * data ("lun. 26/10/2026 - 14:43:41"), "TRYTOMA Sp. z o.o. : 10023", wiersz nagłówków (Kod Nomenklatury, Nazwa
 * Nomenklatury, Kod VAT...) i wiersze tabeli; na ostatniej stronie "2358 linie".
 * <p>
 * Każda komórka tabeli to w PDF-ie osobny napis z własną pozycją, ale zwykły odczyt tekstu skleja sąsiednie komórki
 * ("PIECZYWO CHRUPKIE1kg143"). Dlatego zbieramy napisy z pozycjami, wiersz to napisy na jednej wysokości, a komórka
 * trafia do kolumny, której nagłówek stoi najbliżej. Strony są obrócone, więc która współrzędna jest wierszem,
 * rozpoznajemy po nagłówkach (stoją w jednym wierszu). Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class NomenclatureReportParser {

    private static final Pattern CODE = Pattern.compile("([0-9]{2}){1,3}");
    /** "lun. 26/10/2026 - 14:43:41" (dzień tygodnia po francusku). */
    private static final Pattern GENERATED_AT = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{1,2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
    /** "TRYTOMA  Sp. z o.o. : 10023" po normalizacji. */
    private static final Pattern STORE_NUMBER = Pattern.compile("sp z o o (\\d{3,})$");
    /** "2358 linie" po normalizacji. */
    private static final Pattern DECLARED_ROWS = Pattern.compile("^(\\d+) lini[ae]$");
    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F'};

    /** Kolumny po początku znormalizowanej nazwy nagłówka. */
    private enum Column {
        CODE("kod nomenklatury"),
        NAME("nazwa nomenklatury"),
        VAT("kod vat"),
        UNIT("jednostka"),
        LABEL_COUNT("ilosc etyk"),
        LABEL_FORMAT("format etyk"),
        TILL_MODE("typ przejsc"),
        SALE_TYPE("typ sprzedazy"),
        STOCK_MODE("st mag");

        private final String header;

        Column(String header) {
            this.header = header;
        }

        static Column ofHeader(String normalized) {
            for (Column column : values()) {
                if (normalized.startsWith(column.header)) {
                    return column;
                }
            }
            return null;
        }
    }

    public ParsedNomenclatureReport parse(Path file) throws IOException, InvalidReportException {
        byte[] start = new byte[PDF_SIGNATURE.length];
        try (var input = Files.newInputStream(file)) {
            if (input.readNBytes(start, 0, start.length) != start.length || !Arrays.equals(start, PDF_SIGNATURE)) {
                throw new InvalidReportException("To nie jest plik PDF. Wgraj raport Lista nomenklatur zapisany jako PDF.");
            }
        }
        PdfReader reader;
        try {
            reader = new PdfReader(file.toString());
        } catch (IOException | RuntimeException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku PDF. Sprawdź, czy nie jest uszkodzony.");
        }
        try {
            CellCollector collector = new CellCollector();
            PdfTextExtractor extractor = new PdfTextExtractor(reader, collector);
            ReportReader report = new ReportReader();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                collector.cells.clear();
                extractor.getTextFromPage(page);
                report.onPage(page, collector.cells);
            }
            return report.finish();
        } catch (RuntimeException e) {
            throw new InvalidReportException("Nie udało się odczytać tekstu z pliku PDF. Czy to raport Lista nomenklatur?");
        } finally {
            reader.close();
        }
    }

    /** Napis z PDF-u (jedna komórka) i jego pozycja na stronie: dwie współrzędne, bez założeń o obrocie strony. */
    private record Cell(float a, float b, String text) {
    }

    /**
     * Zbiera napisy strony z pozycjami. Napis dekodujemy przez słowa ({@link ParsedText#getAsPartialWords}), bo tak
     * robi to sam OpenPDF; {@link ParsedText#getText} nie radzi sobie z fontami Identity-H z tego raportu.
     */
    private static final class CellCollector implements TextAssembler {

        private final List<Cell> cells = new ArrayList<>();

        @Override
        public void process(ParsedText text, String contextName) {
            List<Word> words = text.getAsPartialWords();
            if (words.isEmpty()) {
                return;
            }
            String joined = clean(words.stream().map(Word::getText).collect(Collectors.joining()));
            if (!joined.isEmpty()) {
                var startPoint = words.getFirst().getStartPoint();
                cells.add(new Cell(startPoint.get(0), startPoint.get(1), joined));
            }
        }

        @Override
        public void process(Word word, String contextName) {
        }

        @Override
        public void process(FinalText text, String contextName) {
        }

        @Override
        public void renderText(FinalText text) {
        }

        @Override
        public void renderText(ParsedTextImpl text) {
        }

        @Override
        public FinalText endParsingContext(String contextName) {
            return new FinalText("");
        }

        @Override
        public String getWordId() {
            return "word";
        }

        @Override
        public void setPage(int page) {
        }

        @Override
        public void reset() {
        }

        /** Data w nagłówku ma przed sobą znacznik BOM zapisany jako zwykłe znaki ("ï»¿"). */
        private static String clean(String value) {
            return value.replaceAll("[\\uFEFF\\u00EF\\u00BB\\u00BF]", "").replaceAll("\\s+", " ").trim();
        }
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        private String storeNumber;
        private LocalDateTime generatedAt;
        private boolean nomenclatureList;
        private Integer declaredRows;
        private final List<NomenclatureReportRow> rows = new ArrayList<>();
        private final Set<String> seenCodes = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onPage(int page, List<Cell> cells) {
            List<Cell> headers = cells.stream().filter(cell -> Column.ofHeader(normalize(cell.text())) != null).toList();
            Cell codeHeader = headers.stream()
                    .filter(cell -> Column.ofHeader(normalize(cell.text())) == Column.CODE).findFirst().orElse(null);
            Cell nameHeader = headers.stream()
                    .filter(cell -> Column.ofHeader(normalize(cell.text())) == Column.NAME).findFirst().orElse(null);
            // Nagłówki kolumn stoją w jednym wierszu: wspólna współrzędna to wysokość wiersza, druga - pozycja w nim.
            boolean rowIsA = codeHeader == null || nameHeader == null
                    || Math.abs(codeHeader.a() - nameHeader.a()) <= Math.abs(codeHeader.b() - nameHeader.b());

            Map<Column, Float> columns = new LinkedHashMap<>();
            if (codeHeader != null) {
                float headerRow = rowIsA ? codeHeader.a() : codeHeader.b();
                for (Cell header : headers) {
                    if (Math.abs((rowIsA ? header.a() : header.b()) - headerRow) < 1) {
                        columns.putIfAbsent(Column.ofHeader(normalize(header.text())), rowIsA ? header.b() : header.a());
                    }
                }
            }

            // Wiersze w kolejności z góry na dół strony (oś może rosnąć w dół albo w górę, zależnie od obrotu).
            Map<Integer, List<Cell>> lines = new TreeMap<>();
            for (Cell cell : cells) {
                lines.computeIfAbsent(Math.round(rowIsA ? cell.a() : cell.b()), key -> new ArrayList<>()).add(cell);
            }
            for (List<Cell> line : lines.values()) {
                line.sort(Comparator.comparingDouble(cell -> rowIsA ? cell.b() : cell.a()));
                onLine(page, line, columns, rowIsA);
            }
        }

        private void onLine(int page, List<Cell> line, Map<Column, Float> columns, boolean rowIsA) {
            String text = line.stream().map(Cell::text).collect(Collectors.joining(" "));
            String normalized = normalize(text);
            if (normalized.contains("lista nomenklatur")) {
                nomenclatureList = true;
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
            if (columns.isEmpty() || !CODE.matcher(line.getFirst().text()).matches()) {
                return;
            }

            Map<Column, String> values = new LinkedHashMap<>();
            for (Cell cell : line) {
                values.merge(nearest(columns, rowIsA ? cell.b() : cell.a()), cell.text(), (a, b) -> a + " " + b);
            }
            String code = values.get(Column.CODE);
            try {
                if (code == null || !CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("kod nomenklatury \"" + line.getFirst().text()
                            + "\" nie stoi w kolumnie Kod Nomenklatury");
                }
                if (!seenCodes.add(code)) {
                    throw new IllegalArgumentException("kod występuje w pliku więcej niż raz");
                }
                rows.add(new NomenclatureReportRow(
                        code,
                        text(values, Column.NAME, "Nazwa Nomenklatury", 100, true),
                        number(values, Column.VAT, "Kod VAT"),
                        text(values, Column.UNIT, "Jednostka miary", 10, false),
                        number(values, Column.LABEL_COUNT, "Ilość etyk."),
                        number(values, Column.LABEL_FORMAT, "Format etykiet"),
                        text(values, Column.TILL_MODE, "Typ Przejść do Kas", 30, false),
                        text(values, Column.SALE_TYPE, "Typ sprzedaży", 30, false),
                        text(values, Column.STOCK_MODE, "St.Mag./Reappro", 30, false)));
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Strona " + page + ", kod " + line.getFirst().text() + ": " + e.getMessage());
                }
            }
        }

        private ParsedNomenclatureReport finish() throws InvalidReportException {
            if (!nomenclatureList || rows.isEmpty() && errorCount == 0) {
                throw new InvalidReportException("W pliku nie ma tabeli nomenklatur (Kod Nomenklatury, Nazwa "
                        + "Nomenklatury...). Czy to raport Lista nomenklatur?");
            }
            // Rodzina musi mieć swój rejon, a podrodzina rodzinę, inaczej drzewo raportów się rozsypie.
            for (NomenclatureReportRow row : rows) {
                if (row.code().length() > 2 && !seenCodes.contains(row.code().substring(0, row.code().length() - 2))) {
                    errorCount++;
                    if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                        errors.add("Kod " + row.code() + ": w pliku nie ma pozycji nadrzędnej "
                                + row.code().substring(0, row.code().length() - 2));
                    }
                }
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors, errorCount);
            }
            if (declaredRows == null) {
                throw new InvalidReportException("W pliku nie ma ostatniej strony z liczbą linii (np. „2358 linie”). "
                        + "Plik może być niekompletny - wygeneruj go ponownie.");
            }
            if (declaredRows != rows.size()) {
                throw new InvalidReportException("Raport deklaruje " + declaredRows + " linii, a w pliku jest "
                        + rows.size() + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            List<NomenclatureReportRow> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.comparing(NomenclatureReportRow::code));
            return new ParsedNomenclatureReport(storeNumber, generatedAt, List.copyOf(sorted));
        }

        /** Kolumna, której nagłówek zaczyna się najbliżej komórki (liczby są wyśrodkowane, teksty od lewej). */
        private static Column nearest(Map<Column, Float> columns, float position) {
            Column best = null;
            float distance = Float.MAX_VALUE;
            for (Map.Entry<Column, Float> column : columns.entrySet()) {
                float d = Math.abs(column.getValue() - position);
                if (d < distance) {
                    distance = d;
                    best = column.getKey();
                }
            }
            return best;
        }

        private static String text(Map<Column, String> values, Column column, String label, int maxLength,
                boolean required) {
            String value = values.get(column);
            if (value == null || value.isBlank()) {
                if (required) {
                    throw new IllegalArgumentException("brak wartości w kolumnie " + label);
                }
                return null;
            }
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(label + " jest dłuższe niż " + maxLength + " znaków");
            }
            return value;
        }

        private static Short number(Map<Column, String> values, Column column, String label) {
            String value = values.get(column);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Short.valueOf(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " \"" + value + "\" nie jest liczbą");
            }
        }

        private static String normalize(String value) {
            return ReportText.normalize(value);
        }
    }
}
