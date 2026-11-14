package com.intermanage.backend.promotion.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.promotion.dto.ParsedPromotionReport;
import com.intermanage.backend.promotion.dto.PromotionReportRow;

/**
 * Czyta raport "LISTA BIEŻĄCYCH I PRZYSZŁYCH PROMOCJI" z systemu sklepu (PDF, np. Taryfy.pdf, ok. 60 stron). Na każdej
 * stronie: "lun. 02/11/2026 - 06:39:56 Strona: 1 / 64", "TRYTOMA Sp. z o.o. : 10023", tytuł, zakres "(DATY KOŃCA
 * 02/11/26 - 02/11/28)", wiersz nagłówków (Data końca, Kod Artykułu, EAN Prio, Nazwa Artykułu, Data początku,
 * Cena Zak. Katalog., Ceny Sprzed. Przegląd, Cena Promo, Marża) i wiersze promocji. Raport nie ma stopki z liczbą
 * linii, więc kompletność sprawdzamy po numeracji stron ("Strona: N / M").
 * <p>
 * Napisy z pozycjami daje {@link PdfCells}. Lewa część wiersza (daty, kody, nazwa) idzie po kolei, bo nazwa stoi na
 * lewo od swojego nagłówka. Ceny są wyrównane do prawej, więc trafiają do kolumny, w której pasie (od nagłówka do
 * następnego nagłówka) zaczynają się; pusta cena zostaje pusta. Ta sama promocja (ITM8 + okres) drugi raz albo jeden
 * błędny wiersz odrzuca cały plik.
 */
@Component
public class PromotionReportParser {

    /** Ile punktów przed nagłówkiem może zacząć się wartość jego kolumny (krótka liczba zaczyna się za nagłówkiem). */
    private static final float HEADER_SLACK = 12;

    private enum Column {
        END("data konca"),
        ITM("kod artykulu"),
        EAN("ean prio"),
        NAME("nazwa artykulu"),
        START("data poczatku"),
        // Nagłówki dwuwierszowe bywają jednym napisem ("CenaZak.Katalog.") albo dwoma ("Cena" nad "Zak.Katalog.").
        PURCHASE("cena zak", "cenazak", "zak katalog"),
        SALE("ceny sprzed", "cenysprzed", "przeglad"),
        PROMO("cena promo"),
        MARGIN("marza");

        private final String[] headers;

        Column(String... headers) {
            this.headers = headers;
        }

        static Column ofHeader(String normalized) {
            for (Column column : values()) {
                for (String header : column.headers) {
                    if (normalized.startsWith(header)) {
                        return column;
                    }
                }
            }
            return null;
        }
    }

    private static final List<Column> PRICE_COLUMNS = List.of(Column.PURCHASE, Column.SALE, Column.PROMO, Column.MARGIN);

    public ParsedPromotionReport parse(Path file) throws IOException, InvalidReportException {
        if (!PdfCells.isPdf(file)) {
            throw new InvalidReportException("To nie jest plik PDF. Wgraj listę promocji (Taryfy) zapisaną jako PDF.");
        }
        ReportReader report = new ReportReader();
        try {
            PdfCells.read(file, report::onPage);
        } catch (IOException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku PDF. Sprawdź, czy nie jest uszkodzony.");
        } catch (RuntimeException e) {
            throw new InvalidReportException(
                    "Nie udało się odczytać tekstu z pliku PDF. Czy to lista bieżących i przyszłych promocji?");
        }
        return report.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        /** "lun. 02/11/2026 - 06:39:56" (dzień tygodnia po francusku). */
        private static final Pattern GENERATED_AT = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{1,2}:\\d{2}:\\d{2})");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        /** "Strona: 1 / 64". */
        private static final Pattern PAGE = Pattern.compile("Strona\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
        /**
         * "TRYTOMA  Sp. z o.o. : 10023" po normalizacji. W wierszu może stać coś jeszcze (tytuł raportu, data
         * wydruku), zależnie od tego, jak system ułożył nagłówek, więc numer szukamy w całym wierszu.
         */
        private static final Pattern STORE_NUMBER = Pattern.compile("sp z o o (\\d{3,})\\b");
        /** "(DATY KOŃCA 02/11/26 - 02/11/28)". */
        private static final Pattern END_RANGE = Pattern.compile("(\\d{2}/\\d{2}/\\d{2})\\s*-\\s*(\\d{2}/\\d{2}/\\d{2})");
        private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{2}");
        private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yy");
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        private static final Pattern NUMBER = Pattern.compile("-?\\d+(,\\d+)?");
        private static final int ITM8_LENGTH = 13;
        private static final int NAME_LENGTH = 100;

        private String storeNumber;
        private LocalDateTime generatedAt;
        private LocalDate endFrom;
        private LocalDate endTo;
        private boolean promotionList;
        private int pageCount;
        private Integer declaredPages;
        private final List<PromotionReportRow> rows = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onPage(int page, int pages, List<Cell> cells) {
            pageCount = pages;
            List<Cell> headers = cells.stream().filter(cell -> Column.ofHeader(normalize(cell.text())) != null).toList();
            Cell endHeader = header(headers, Column.END);
            Cell itmHeader = header(headers, Column.ITM);
            // Nagłówki stoją w jednym wierszu: wspólna współrzędna to wysokość wiersza, druga - pozycja w nim.
            boolean rowIsA = endHeader == null || itmHeader == null
                    || Math.abs(endHeader.a() - itmHeader.a()) <= Math.abs(endHeader.b() - itmHeader.b());

            Map<Column, Float> columns = new EnumMap<>(Column.class);
            if (endHeader != null) {
                float headerRow = rowIsA ? endHeader.a() : endHeader.b();
                for (Cell header : headers) {
                    // Nagłówki dwuwierszowe stoją trochę wyżej i niżej niż reszta; kolumna zaczyna się od bardziej
                    // wysuniętej w lewo linii.
                    if (Math.abs((rowIsA ? header.a() : header.b()) - headerRow) < 8) {
                        columns.merge(Column.ofHeader(normalize(header.text())), rowIsA ? header.b() : header.a(),
                                Math::min);
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
            if (normalized.contains("promocji")) {
                promotionList = true;
            }
            Matcher pageNumber = PAGE.matcher(text);
            if (pageNumber.find()) {
                declaredPages = Integer.parseInt(pageNumber.group(2));
            }
            // Numer sklepu przed datą wydruku: oba bywają w jednym wierszu, a data kończy czytanie wiersza.
            Matcher store = STORE_NUMBER.matcher(normalized);
            if (store.find()) {
                if (storeNumber == null) {
                    storeNumber = store.group(1);
                }
                if (!GENERATED_AT.matcher(text).find()) {
                    return;
                }
            }
            Matcher generated = GENERATED_AT.matcher(text);
            if (generated.find()) {
                if (generatedAt == null) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                }
                return;
            }
            if (normalized.startsWith("daty konca")) {
                Matcher range = END_RANGE.matcher(text);
                if (endFrom == null && range.find()) {
                    try {
                        endFrom = LocalDate.parse(range.group(1), SHORT_DATE);
                        endTo = LocalDate.parse(range.group(2), SHORT_DATE);
                    } catch (DateTimeParseException ignored) {
                        // Bez zakresu weźmiemy daty końca z samych promocji.
                    }
                }
                return;
            }
            if (line.size() < 2 || !DATE.matcher(line.getFirst().text()).matches()
                    || !DIGITS.matcher(line.get(1).text()).matches()) {
                return;
            }

            String itm8 = line.get(1).text();
            try {
                if (!columns.keySet().containsAll(List.of(Column.START, Column.PURCHASE, Column.SALE, Column.PROMO,
                        Column.MARGIN))) {
                    throw new IllegalArgumentException("na stronie brakuje wiersza z nagłówkami kolumn");
                }
                PromotionReportRow row = row(line, columns, rowIsA);
                if (row != null) {
                    rows.add(row);
                }
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Strona " + page + ", artykuł " + itm8 + ": " + e.getMessage());
                }
            }
        }

        /** Promocja z linii albo {@code null}, gdy linia nie ma nazwy artykułu (pomijana). */
        private PromotionReportRow row(List<Cell> line, Map<Column, Float> columns, boolean rowIsA) {
            float startColumn = columns.get(Column.START);
            int startIndex = -1;
            for (int i = 2; i < line.size(); i++) {
                if (DATE.matcher(line.get(i).text()).matches()
                        && position(line.get(i), rowIsA) >= startColumn - HEADER_SLACK * 2) {
                    startIndex = i;
                    break;
                }
            }
            if (startIndex < 0) {
                throw new IllegalArgumentException("brak daty początku promocji");
            }

            LocalDate endDate = date(line.getFirst().text(), "Data końca");
            String itm8 = itm8(line.get(1).text());
            List<Cell> middle = line.subList(2, startIndex);
            String ean = null;
            if (!middle.isEmpty() && DIGITS.matcher(middle.getFirst().text()).matches()) {
                ean = middle.getFirst().text();
                if (ean.length() > 14) {
                    throw new IllegalArgumentException("EAN \"" + ean + "\" ma więcej niż 14 cyfr");
                }
                middle = middle.subList(1, middle.size());
            }
            String name = middle.stream().map(Cell::text).collect(Collectors.joining(" ")).trim();
            if (name.isEmpty()) {
                // System sklepu drukuje czasem promocję bez nazwy artykułu; taką linię pomijamy.
                return null;
            }
            if (name.length() > NAME_LENGTH) {
                throw new IllegalArgumentException("nazwa jest dłuższa niż " + NAME_LENGTH + " znaków");
            }
            LocalDate startDate = date(line.get(startIndex).text(), "Data początku");

            Map<Column, BigDecimal> prices = new EnumMap<>(Column.class);
            for (Cell cell : line.subList(startIndex + 1, line.size())) {
                Column column = priceColumn(columns, position(cell, rowIsA));
                if (column == null) {
                    throw new IllegalArgumentException("wartość \"" + cell.text() + "\" nie stoi w kolumnie z ceną");
                }
                if (prices.containsKey(column)) {
                    throw new IllegalArgumentException("dwie wartości w jednej kolumnie (\"" + cell.text() + "\")");
                }
                prices.put(column, number(cell.text()));
            }

            if (!seen.add(itm8 + "|" + startDate + "|" + endDate)) {
                throw new IllegalArgumentException("ta sama promocja (artykuł i okres) występuje w pliku więcej niż raz");
            }
            return new PromotionReportRow(itm8, ean, name, startDate, endDate, prices.get(Column.PURCHASE),
                    prices.get(Column.SALE), prices.get(Column.PROMO), prices.get(Column.MARGIN));
        }

        private ParsedPromotionReport finish() throws InvalidReportException {
            if (!promotionList || rows.isEmpty() && errorCount == 0) {
                throw new InvalidReportException("W pliku nie ma tabeli promocji (Data końca, Kod Artykułu, EAN "
                        + "Prio...). Czy to lista bieżących i przyszłych promocji?");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (storeNumber == null) {
                // Bez numeru nie wiadomo, czyje to promocje: lista innego sklepu wgrałaby się do wybranego (lokalne
                // ITM8 99… trafiłyby wtedy na inne artykuły).
                throw new InvalidReportException("Nie widać numeru sklepu w nagłówku pliku (\"TRYTOMA Sp. z o.o. : "
                        + "10023\"). Wgraj listę promocji wydrukowaną z systemu sklepu.");
            }
            if (declaredPages != null && declaredPages != pageCount) {
                throw new InvalidReportException("Raport ma " + declaredPages + " stron, a w pliku jest " + pageCount
                        + ". Plik może być niekompletny - wydrukuj go ponownie.");
            }
            LocalDate from = endFrom != null ? endFrom
                    : rows.stream().map(PromotionReportRow::endDate).min(Comparator.naturalOrder()).orElseThrow();
            LocalDate to = endTo != null ? endTo
                    : rows.stream().map(PromotionReportRow::endDate).max(Comparator.naturalOrder()).orElseThrow();
            return new ParsedPromotionReport(storeNumber, generatedAt, from, to, List.copyOf(rows));
        }

        /** Kolumna ceny, w której pasie zaczyna się wartość: od nagłówka (z zapasem) do następnego nagłówka. */
        private static Column priceColumn(Map<Column, Float> columns, float position) {
            Column found = null;
            for (Column column : PRICE_COLUMNS) {
                if (position >= columns.get(column) - HEADER_SLACK) {
                    found = column;
                }
            }
            return found;
        }

        private static Cell header(List<Cell> headers, Column column) {
            return headers.stream().filter(cell -> Column.ofHeader(normalize(cell.text())) == column).findFirst()
                    .orElse(null);
        }

        private static float position(Cell cell, boolean rowIsA) {
            return rowIsA ? cell.b() : cell.a();
        }

        private static String itm8(String value) {
            if (value.length() > ITM8_LENGTH) {
                throw new IllegalArgumentException("Kod Artykułu \"" + value + "\" ma więcej niż " + ITM8_LENGTH
                        + " cyfr");
            }
            return "0".repeat(ITM8_LENGTH - value.length()) + value;
        }

        private static LocalDate date(String value, String label) {
            try {
                return LocalDate.parse(value, SHORT_DATE);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(label + " \"" + value + "\" nie jest datą");
            }
        }

        private static BigDecimal number(String value) {
            String plain = value.replaceAll("[\\s\\u00A0]", "");
            if (!NUMBER.matcher(plain).matches()) {
                throw new IllegalArgumentException("\"" + value + "\" nie jest liczbą");
            }
            return new BigDecimal(plain.replace(',', '.'));
        }

        private static String normalize(String value) {
            return ReportText.normalize(value);
        }
    }
}
