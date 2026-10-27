package com.intermanage.backend.document.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.ReportText;
import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;
import com.intermanage.backend.document.dto.DocumentLineRow;
import com.intermanage.backend.document.dto.ParsedDocumentReport;
import com.intermanage.backend.document.entity.DocumentType;
import com.intermanage.backend.product.component.ProductReportParser;

/**
 * Czyta raport "Statystyka: Wejścia/Wyjścia" z systemu sklepu. Układ pliku: "sam. 24/10/2026 - 16:10:50  6745 linie",
 * "TRYTOMA Sp. z o.o. - 10023" (numer sklepu), "Wybór danych : OD 20/10/2026 do 24/10/2026", wiersz z nazwami
 * kolumn (Code nomenclature ... Marge en %), po jednym wierszu na linię dokumentu i na końcu "Razem:".
 * Kolumny szukamy po nazwie (bez znaków diakrytycznych i wielkości liter). Zapisujemy typy z {@link DocumentType};
 * linie innych typów (np. MM) są liczone, ale nie sprawdzane. Jakikolwiek błąd w zapisywanej linii odrzuca cały plik.
 */
@Component
public class DocumentReportParser {

    private final XlsxReader xlsxReader;

    public DocumentReportParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public ParsedDocumentReport parse(Path file) throws IOException, InvalidReportException {
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

        private static final Pattern GENERATED_AT = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})\\s*-\\s*(\\d{1,2}:\\d{2}:\\d{2})");
        private static final Pattern DECLARED_LINES = Pattern.compile("(\\d+)\\s+lini", Pattern.CASE_INSENSITIVE);
        /** "TRYTOMA  Sp. z o.o. - 10023": numer sklepu po myślniku na końcu. */
        private static final Pattern STORE_NUMBER = Pattern.compile("-\\s*(\\d{3,})\\s*$");
        private static final Pattern PERIOD = Pattern.compile(
                "OD\\s+(\\d{2}/\\d{2}/\\d{4})\\s+do\\s+(\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE);
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy H:mm:ss");
        private static final Pattern DIGITS = Pattern.compile("\\d+");
        /** Mieści się w najwęższej kolumnie kwotowej, numeric(14, 4). */
        private static final BigDecimal MAX_DECIMAL = new BigDecimal("10000000000");

        private static final String TYPE = "type document";
        private static final String NUMBER = "numero document interne";
        private static final String DOCUMENT_DATE = "date document";
        private static final String SUPPLIER_CODE = "code fournisseur";
        private static final String SUPPLIER_NAME = "libelle fournisseur";
        private static final String SUPPLIER_DOCUMENT = "numero document fournisseur";
        private static final String LINE = "n ligne";
        private static final String ITM8 = "itm8";
        private static final String QUANTITY = "quantite";
        private static final List<String> REQUIRED_COLUMNS = List.of(TYPE, NUMBER, DOCUMENT_DATE, LINE, ITM8, QUANTITY);
        /** Nazwy kolumn do komunikatów o błędach, tak jak w raporcie. */
        private static final Map<String, String> LABELS = Map.ofEntries(
                Map.entry(TYPE, "Type Document"), Map.entry(NUMBER, "Numéro document interne"),
                Map.entry(DOCUMENT_DATE, "Date Document"), Map.entry(LINE, "N° ligne"), Map.entry(ITM8, "ITM8"),
                Map.entry(QUANTITY, "Quantité"));

        private LocalDateTime generatedAt;
        private Integer declaredLines;
        private String storeNumber;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private Map<String, Integer> columns;
        private boolean footerReached;
        private int totalLines;
        private final List<DocumentLineRow> lines = new ArrayList<>();
        private final Map<String, DocumentLineRow> headerByDocument = new HashMap<>();
        private final Set<String> seenLines = new HashSet<>();
        private final Map<String, Integer> skipped = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        private void onRow(int rowNumber, List<String> cells) {
            if (columns == null) {
                readPreamble(cells);
                return;
            }
            if (footerReached || isBlank(cells)) {
                return;
            }
            if (firstNonBlank(cells).trim().toLowerCase(Locale.ROOT).startsWith("razem")) {
                footerReached = true;
                return;
            }
            totalLines++;
            try {
                String code = text(cells, TYPE, 10);
                if (code == null) {
                    throw new IllegalArgumentException("brak typu dokumentu");
                }
                DocumentType type = DocumentType.fromCode(code).orElse(null);
                if (type == null) {
                    skipped.merge(code.toUpperCase(Locale.ROOT), 1, Integer::sum);
                    return;
                }
                DocumentLineRow line = toLine(type, cells);
                String document = type.name() + " " + line.documentNumber();
                if (!seenLines.add(document + "/" + line.lineKey())) {
                    throw new IllegalArgumentException("linia " + line.lineNumber() + " z ITM8 " + line.itm8()
                            + " dokumentu " + line.documentNumber() + " (" + type.singular().toLowerCase(Locale.ROOT)
                            + ") występuje w pliku więcej niż raz");
                }
                DocumentLineRow header = headerByDocument.putIfAbsent(document, line);
                if (header != null && !sameHeader(header, line)) {
                    throw new IllegalArgumentException("dokument " + line.documentNumber() + " ("
                            + type.singular().toLowerCase(Locale.ROOT)
                            + ") ma w pliku różną datę albo numer dokumentu dostawcy");
                }
                lines.add(line);
            } catch (IllegalArgumentException e) {
                errorCount++;
                if (errors.size() < ProductReportParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        /** Wiersze nad nagłówkiem: data wygenerowania, liczba linii, numer sklepu, zakres dat i sam nagłówek. */
        private void readPreamble(List<String> cells) {
            Map<String, Integer> header = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                String value = cells.get(i);
                if (value != null && !value.isBlank()) {
                    header.putIfAbsent(ReportText.normalize(value), i);
                }
            }
            if (header.containsKey(TYPE) && header.containsKey(ITM8)) {
                columns = header;
                return;
            }
            for (String value : cells) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                Matcher generated = GENERATED_AT.matcher(value);
                if (generatedAt == null && generated.find()) {
                    try {
                        generatedAt = LocalDateTime.parse(generated.group(1) + " " + generated.group(2), DATE_TIME);
                    } catch (DateTimeParseException ignored) {
                        // Data jest tylko informacyjna.
                    }
                    Matcher lines = DECLARED_LINES.matcher(value);
                    if (lines.find()) {
                        declaredLines = Integer.parseInt(lines.group(1));
                    }
                    continue;
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
                    continue;
                }
                Matcher store = STORE_NUMBER.matcher(value.trim());
                if (storeNumber == null && store.find()) {
                    storeNumber = store.group(1);
                }
            }
        }

        private ParsedDocumentReport finish() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("W pliku nie ma wiersza z nagłówkami kolumn (Type Document, ITM8...). "
                        + "Czy to raport Wejścia/Wyjścia?");
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
            if (totalLines == 0) {
                throw new InvalidReportException("Plik nie zawiera żadnych linii dokumentów");
            }
            // System sklepu wlicza do "6745 linie" wiersz z nazwami kolumn (w pliku jest 6744 linii dokumentów).
            if (declaredLines != null && declaredLines != totalLines && declaredLines != totalLines + 1) {
                throw new InvalidReportException("Raport deklaruje " + declaredLines + " linii, a w pliku jest "
                        + totalLines + ". Plik może być niekompletny - wygeneruj go ponownie.");
            }
            return new ParsedDocumentReport(storeNumber, generatedAt, periodFrom, periodTo, totalLines,
                    List.copyOf(lines), Map.copyOf(skipped));
        }

        private DocumentLineRow toLine(DocumentType type, List<String> cells) {
            String number = text(cells, NUMBER, 30);
            if (number == null) {
                throw new IllegalArgumentException("brak numeru dokumentu (Numéro document interne)");
            }
            return new DocumentLineRow(
                    type,
                    number,
                    dateTime(cells, DOCUMENT_DATE),
                    supplierDocument(cells),
                    lineNumber(cells),
                    text(cells, SUPPLIER_CODE, 10),
                    text(cells, SUPPLIER_NAME, 100),
                    text(cells, "code nomenclature", 30),
                    text(cells, "libelle nomenclature", 60),
                    code(cells, ITM8, 13, true),
                    code(cells, "ean13", 14, false),
                    text(cells, "libelle article", 100),
                    text(cells, "marque", 50),
                    text(cells, "unite mesure", 20),
                    required(decimal(cells, QUANTITY, 3), QUANTITY),
                    decimal(cells, "prix achat fifo", 4),
                    decimal(cells, "prix vente actuel", 4),
                    decimal(cells, "prix vente document", 4),
                    decimal(cells, "valeur fifo", 4),
                    decimal(cells, "valeur vente ttc", 4),
                    decimal(cells, "valeur vente ht", 4),
                    vatRate(cells),
                    decimal(cells, "valeur tva", 4),
                    decimal(cells, "marge en valeur", 4),
                    decimal(cells, "marge en", 2));
        }

        /** Dostawca bywa różny w liniach (likwidacje, przeniesienia), data i numer dokumentu dostawcy - nie. */
        private static boolean sameHeader(DocumentLineRow a, DocumentLineRow b) {
            return a.documentDate().equals(b.documentDate())
                    && Objects.equals(a.supplierDocumentNumber(), b.supplierDocumentNumber());
        }

        /** "0" w raporcie oznacza brak numeru dokumentu dostawcy. */
        private String supplierDocument(List<String> cells) {
            String value = text(cells, SUPPLIER_DOCUMENT, 60);
            return value == null || value.equals("0") ? null : value;
        }

        private int lineNumber(List<String> cells) {
            BigDecimal value = required(decimal(cells, LINE, 6), LINE);
            if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                throw new IllegalArgumentException("N° ligne \"" + value.stripTrailingZeros().toPlainString()
                        + "\" musi być dodatnią liczbą całkowitą");
            }
            return value.intValueExact();
        }

        /** "21/10/2026 00:00:00" jako tekst albo data Excela zapisana liczbą dni od 1899-12-30. */
        private LocalDateTime dateTime(List<String> cells, String column) {
            String value = text(cells, column, 30);
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + LABELS.getOrDefault(column, column));
            }
            try {
                if (value.matches("\\d+(\\.\\d+)?")) {
                    BigDecimal days = new BigDecimal(value);
                    long seconds = days.subtract(new BigDecimal(days.toBigInteger())).multiply(BigDecimal.valueOf(86_400))
                            .setScale(0, RoundingMode.HALF_UP).longValue();
                    return LocalDate.of(1899, 12, 30).plusDays(days.longValue()).atStartOfDay().plusSeconds(seconds);
                }
                return value.length() <= 10 ? LocalDate.parse(value, DATE).atStartOfDay()
                        : LocalDateTime.parse(value.replaceAll("\\s+", " "), DATE_TIME);
            } catch (DateTimeParseException | ArithmeticException e) {
                throw new IllegalArgumentException("data dokumentu \"" + value + "\" nie jest w formacie DD/MM/RRRR");
            }
        }

        /** TVA to w tym raporcie stawka w procentach (5, 8, 23). */
        private Short vatRate(List<String> cells) {
            BigDecimal value = decimal(cells, "tva", 2);
            if (value == null) {
                return null;
            }
            if (value.stripTrailingZeros().scale() > 0 || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("stawka VAT \"" + value.stripTrailingZeros().toPlainString()
                        + "\" musi być liczbą całkowitą od 0 do 100");
            }
            return value.shortValueExact();
        }

        /** Kod z samych cyfr (EAN, ITM8). Zera wiodące są częścią kodu. */
        private String code(List<String> cells, String column, int maxLength, boolean required) {
            String value = text(cells, column, maxLength);
            if (value == null) {
                if (required) {
                    throw new IllegalArgumentException("brak wartości w kolumnie " + column.toUpperCase(Locale.ROOT));
                }
                return null;
            }
            if (!DIGITS.matcher(value).matches()) {
                throw new IllegalArgumentException(column.toUpperCase(Locale.ROOT) + " \"" + value + "\" musi składać się z cyfr");
            }
            return value;
        }

        private String text(List<String> cells, String column, int maxLength) {
            String value = cell(cells, column);
            if (value == null || value.isBlank()) {
                return null;
            }
            value = value.trim();
            if (value.length() > maxLength) {
                throw new IllegalArgumentException(LABELS.getOrDefault(column, column) + " jest dłuższe niż "
                        + maxLength + " znaków");
            }
            return value;
        }

        /** Excel zapisuje liczby binarnie (np. 79.989999999999995), więc zaokrąglamy do dokładności kolumny. */
        private BigDecimal decimal(List<String> cells, String column, int scale) {
            String value = text(cells, column, 64);
            if (value == null) {
                return null;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(value.replace("%", "").replace(',', '.').replace(" ", ""))
                        .setScale(scale, RoundingMode.HALF_UP);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(LABELS.getOrDefault(column, column) + " \"" + value + "\" nie jest liczbą");
            }
            if (number.abs().compareTo(MAX_DECIMAL) >= 0) {
                throw new IllegalArgumentException(LABELS.getOrDefault(column, column) + " \"" + value
                        + "\" jest poza dopuszczalnym zakresem");
            }
            return number;
        }

        private static BigDecimal required(BigDecimal value, String column) {
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + LABELS.getOrDefault(column, column));
            }
            return value;
        }

        /** Wartość kolumny po nazwie; {@code null}, gdy raport jej nie ma albo komórka jest pusta. */
        private String cell(List<String> cells, String column) {
            Integer index = columns.get(column);
            return index == null || index >= cells.size() ? null : cells.get(index);
        }

        private static boolean isBlank(List<String> cells) {
            return cells.stream().allMatch(value -> value == null || value.isBlank());
        }

        private static String firstNonBlank(List<String> cells) {
            return cells.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }
    }
}
