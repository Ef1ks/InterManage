package com.intermanage.backend.stocktake.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
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
import com.intermanage.backend.product.component.ProductReportParser;
import com.intermanage.backend.stocktake.dto.ParsedStocktakeReport;
import com.intermanage.backend.stocktake.dto.StocktakeReportRow;

/**
 * Czyta raport "PORÓWNANIE STANÓW RZECZYWISTYCH I TEORETYCZNYCH WG.ART." z systemu sklepu (PDF, np. 278 stron i ok.
 * 20 tys. artykułów). Na każdej stronie: "mar. 30/06/2026 - 06:58:24 Strona: 1 / 278", "TRYTOMA Sp. z o.o. : 10023",
 * tytuł, "INWENT POLROCZ 2026 Numer : 100 22/06/2026 - Z art. wyzerowanymi", nagłówki i wiersze artykułów. Pierwsza
 * strona ma ramkę wyboru ("ITM8 : 0000000000000 - 9999999999999"), przedostatnia stopkę "19589 linie", ostatnia wyceny
 * z sumą ("SUMA 44318,89 -11819,96 32498,93"). Z nich sprawdzamy, czy odczytano cały plik.
 * <p>
 * Wiersz idzie po kolei, bo liczby są wyrównane do prawej, a pola się nie zlewają: ITM8, EAN, "nazwa - marka",
 * opcjonalny Typ.Blok., cztery liczby (rzeczywisty, teoretyczny, różnica ilości, różnica wartości), opcjonalny rodzaj
 * artykułu. Jeden błędny wiersz odrzuca cały plik.
 */
@Component
public class StocktakeReportParser {

    public ParsedStocktakeReport parse(Path file) throws IOException, InvalidReportException {
        if (!PdfCells.isPdf(file)) {
            throw new InvalidReportException("To nie jest plik PDF. Wgraj porównanie stanów zapisane jako PDF.");
        }
        ReportReader report = new ReportReader();
        try {
            PdfCells.read(file, report::onPage);
        } catch (IOException e) {
            throw new InvalidReportException("Nie udało się odczytać pliku PDF. Sprawdź, czy nie jest uszkodzony.");
        } catch (RuntimeException e) {
            throw new InvalidReportException(
                    "Nie udało się odczytać tekstu z pliku PDF. Czy to porównanie stanów rzeczywistych i teoretycznych?");
        }
        return report.finish();
    }

    /** Stan czytania jednego pliku; bean parsera jest współdzielony, więc każdy import dostaje własny. */
    private static final class ReportReader {

        private static final Pattern GENERATED_AT = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{1,2}:\\d{2}:\\d{2})");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        private static final Pattern PAGE = Pattern.compile("Strona\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
        private static final Pattern STORE_NUMBER = Pattern.compile("sp z o o (\\d{3,})$");
        /** "INWENT POLROCZ 2026 Numer : 100 22/06/2026 - Z art. wyzerowanymi". */
        private static final Pattern STOCKTAKE = Pattern.compile(
                "^(.+?)\\s+Numer\\s*:\\s*(\\S+)\\s+(\\d{2}/\\d{2}/\\d{4})");
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private static final Pattern ITM8 = Pattern.compile("\\d{13}");
        private static final Pattern EAN = Pattern.compile("\\d{1,14}");
        private static final Pattern NUMBER = Pattern.compile("-?\\d+(,\\d+)?");
        private static final Pattern LINE_COUNT = Pattern.compile("^(\\d+) linie$");
        /** Ramka raportu całego sklepu: wszystkie kody, od samych zer do samych dziewiątek. */
        private static final Pattern WHOLE_STORE = Pattern.compile("ITM8\\s*:\\s*0{13}\\s*-\\s*9{13}");
        private static final int QUANTITIES = 4;
        /** Suma wierszy ma 3 miejsca po przecinku, a suma raportu 2, więc przy tysiącach wierszy różni się o grosze. */
        private static final BigDecimal SUM_TOLERANCE = BigDecimal.ONE;
        /** Ile punktów mogą się rozjeżdżać napisy jednego wiersza albo jednej kolumny podsumowania. */
        private static final float SAME_POSITION = 2;

        private String storeNumber;
        private LocalDateTime generatedAt;
        private String stocktakeName;
        private String stocktakeNumber;
        private LocalDate stocktakeDate;
        private String selection;
        private boolean selectionNext;
        private boolean titleSeen;
        private BigDecimal declaredValue;
        private Integer declaredLines;
        private Integer declaredPages;
        private int pageCount;
        /** Oś wiersza z nagłówków poprzedniej strony (ostatnia strona nagłówków nie ma). */
        private boolean rowIsA;
        private final List<StocktakeReportRow> rows = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onPage(int page, int pages, List<Cell> cells) {
            pageCount = pages;
            detectRowAxis(cells);
            readPurchaseSum(cells);
            Map<Integer, List<Cell>> lines = new TreeMap<>(rowIsA ? Comparator.<Integer>naturalOrder()
                    : Comparator.<Integer>reverseOrder());
            for (Cell cell : cells) {
                lines.computeIfAbsent(Math.round(rowIsA ? cell.a() : cell.b()), key -> new ArrayList<>()).add(cell);
            }
            for (List<Cell> line : lines.values()) {
                line.sort(Comparator.comparingDouble(cell -> rowIsA ? cell.b() : cell.a()));
                onLine(page, line);
            }
        }

        /** Nagłówki kolumn liczbowych stoją w jednym wierszu: wspólna współrzędna to wysokość wiersza. */
        private void detectRowAxis(List<Cell> cells) {
            Cell first = null;
            Cell second = null;
            for (Cell cell : cells) {
                String text = ReportText.normalize(cell.text());
                if (text.startsWith("st rzecz")) {
                    first = cell;
                } else if (text.startsWith("niezg wart")) {
                    second = cell;
                }
            }
            if (first != null && second != null) {
                rowIsA = Math.abs(first.a() - second.a()) <= Math.abs(first.b() - second.b());
            }
        }

        private void onLine(int page, List<Cell> line) {
            if (ITM8.matcher(line.getFirst().text()).matches() && line.size() > 2) {
                onRow(page, line);
                return;
            }
            String text = line.stream().map(Cell::text).collect(Collectors.joining(" "));
            String normalized = ReportText.normalize(text);
            if (selectionNext) {
                selectionNext = false;
                selection = text.length() > 200 ? text.substring(0, 200) : text;
                return;
            }
            if (normalized.startsWith("ramka wyboru")) {
                selectionNext = true;
                return;
            }
            if (normalized.contains("porownanie stanow rzeczywistych")) {
                titleSeen = true;
                return;
            }
            Matcher pageNumber = PAGE.matcher(text);
            if (pageNumber.find()) {
                declaredPages = Integer.parseInt(pageNumber.group(2));
                Matcher generated = GENERATED_AT.matcher(text);
                if (generatedAt == null && generated.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Brak daty odrzuci plik przy końcu odczytu.
                    }
                }
                return;
            }
            Matcher store = STORE_NUMBER.matcher(normalized);
            if (store.find()) {
                if (storeNumber == null) {
                    storeNumber = store.group(1);
                }
                return;
            }
            Matcher stocktake = STOCKTAKE.matcher(text);
            if (stocktakeNumber == null && stocktake.find()) {
                try {
                    stocktakeDate = LocalDate.parse(stocktake.group(3), DATE);
                    stocktakeName = stocktake.group(1).trim();
                    stocktakeNumber = stocktake.group(2);
                } catch (DateTimeParseException ignored) {
                    // Brak daty odrzuci plik przy końcu odczytu.
                }
                return;
            }
            Matcher lineCount = LINE_COUNT.matcher(normalized);
            if (lineCount.find()) {
                declaredLines = Integer.parseInt(lineCount.group(1));
                return;
            }
        }

        /**
         * Suma z wyceny w cenach zakupu. Wycena w cenach sprzedaży stoi w tym samym wierszu po prawej, a napis "SUMA"
         * jest o punkt niżej niż jego liczby, więc idziemy po pozycjach: od nagłówka tabeli do napisu pod nim, a stamtąd
         * do pierwszej liczby po prawej.
         */
        private void readPurchaseSum(List<Cell> cells) {
            Cell heading = cells.stream()
                    .filter(cell -> ReportText.normalize(cell.text()).equals("wycena w cenach zakupu")).findFirst()
                    .orElse(null);
            if (heading == null || declaredValue != null) {
                return;
            }
            Cell label = cells.stream()
                    .filter(cell -> cell.text().equals("SUMA")
                            && Math.abs(across(cell) - across(heading)) <= SAME_POSITION)
                    .min(Comparator.comparingDouble(cell -> Math.abs(row(cell) - row(heading)))).orElse(null);
            if (label == null) {
                return;
            }
            cells.stream()
                    .filter(cell -> Math.abs(row(cell) - row(label)) <= SAME_POSITION && across(cell) > across(label))
                    .min(Comparator.comparingDouble(this::across))
                    .filter(cell -> isNumber(cell.text()))
                    .ifPresent(cell -> declaredValue = number(cell.text()));
        }

        private float row(Cell cell) {
            return rowIsA ? cell.a() : cell.b();
        }

        private float across(Cell cell) {
            return rowIsA ? cell.b() : cell.a();
        }

        private void onRow(int page, List<Cell> line) {
            String itm8 = line.getFirst().text();
            try {
                StocktakeReportRow row = row(line);
                if (!seen.add(itm8)) {
                    throw new IllegalArgumentException("artykuł występuje w pliku więcej niż raz");
                }
                rows.add(row);
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Strona " + page + ", artykuł " + itm8 + ": " + e.getMessage());
                }
            }
        }

        private static StocktakeReportRow row(List<Cell> line) {
            int index = 1;
            String ean = null;
            if (EAN.matcher(line.get(index).text()).matches()) {
                ean = line.get(index++).text();
            }
            if (index >= line.size()) {
                throw new IllegalArgumentException("brak nazwy artykułu");
            }
            String label = line.get(index++).text();
            int separator = label.lastIndexOf(" - ");
            String name = separator > 0 ? label.substring(0, separator).trim() : label;
            String brand = separator > 0 ? label.substring(separator + 3).trim() : null;

            String blockType = null;
            if (index < line.size() && !isNumber(line.get(index).text())) {
                blockType = line.get(index++).text();
            }
            if (line.size() < index + QUANTITIES) {
                throw new IllegalArgumentException("brakuje ilości albo wartości");
            }
            BigDecimal real = number(line.get(index).text());
            BigDecimal theoretical = number(line.get(index + 1).text());
            BigDecimal difference = number(line.get(index + 2).text());
            BigDecimal value = number(line.get(index + 3).text());
            index += QUANTITIES;
            String kind = index < line.size()
                    ? line.subList(index, line.size()).stream().map(Cell::text).collect(Collectors.joining(" "))
                    : null;
            return new StocktakeReportRow(line.getFirst().text(), ean, name, brand == null || brand.isEmpty() ? null : brand,
                    blockType, real, theoretical, difference, value, kind);
        }

        private ParsedStocktakeReport finish() throws InvalidReportException {
            if (!titleSeen) {
                throw new InvalidReportException("To nie jest porównanie stanów rzeczywistych i teoretycznych.");
            }
            if (errorCount > 0) {
                throw new InvalidReportException(
                        "Plik zawiera błędne wiersze (" + errorCount + "). Nic nie zostało zaimportowane.", errors,
                        errorCount);
            }
            if (generatedAt == null || stocktakeNumber == null) {
                throw new InvalidReportException("Raport nie ma daty wydruku albo numeru inwentury w nagłówku.");
            }
            if (declaredPages != null && declaredPages != pageCount) {
                throw new InvalidReportException("Raport ma " + declaredPages + " stron, a w pliku jest " + pageCount
                        + ". Plik może być niekompletny - wydrukuj go ponownie.");
            }
            if (declaredLines != null && declaredLines != rows.size()) {
                throw new InvalidReportException("Raport ma " + declaredLines + " linii, a odczytano " + rows.size()
                        + ". Plik może być niekompletny - wydrukuj go ponownie.");
            }
            BigDecimal sum = rows.stream().map(StocktakeReportRow::differenceValue).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            if (declaredValue != null && sum.subtract(declaredValue).abs().compareTo(SUM_TOLERANCE) > 0) {
                throw new InvalidReportException("Suma różnic z wierszy (" + sum + ") nie zgadza się z sumą raportu ("
                        + declaredValue + "). Plik może być niekompletny - wydrukuj go ponownie.");
            }
            boolean wholeStore = selection != null && WHOLE_STORE.matcher(selection).find();
            return new ParsedStocktakeReport(storeNumber, generatedAt, stocktakeName, stocktakeNumber, stocktakeDate,
                    selection, wholeStore, pageCount, declaredValue != null ? declaredValue : sum, List.copyOf(rows));
        }

        private static boolean isNumber(String value) {
            return NUMBER.matcher(value.replaceAll("[\\s\\u00A0]", "")).matches();
        }

        private static BigDecimal number(String value) {
            String plain = value.replaceAll("[\\s\\u00A0]", "");
            if (!NUMBER.matcher(plain).matches()) {
                throw new IllegalArgumentException("\"" + value + "\" nie jest liczbą");
            }
            return new BigDecimal(plain.replace(',', '.'));
        }
    }
}
