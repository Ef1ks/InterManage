package com.intermanage.backend.basedelivery.component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.intermanage.backend.basedelivery.dto.TemporaryNoteResponse;
import com.intermanage.backend.common.exception.InvalidReportException;

/**
 * „Tymczasowy bon dostawy” z Mercalys (np. {@code roznice.xlsx}): dostawa z bazy wprowadzona do programu sklepu,
 * osobno dla dostawcy (538 spożywka, 88110 alkohol). Nad tabelą w kolumnie A nazwa dostawcy, „Dostawca 539”,
 * „Numer dostawy: 10727”, „Data : 03/11/2026 18:06:39”; nagłówek Kod ITM8, EAN Prio, Referencja dostawcy (ITM8
 * z pliku bazy), Nazwa artykułu, VAT, Ilość dost., Jednostk. cena zakupu, Jednostkowa C.S., Wartość zakupu netto,
 * Marża %; stopka „Ilość linii: 000004”. Polskie litery w nagłówkach system zapisuje uszkodzone, więc kolumny
 * rozpoznajemy po fragmentach nazw z samych liter łacińskich.
 */
@Component
public class TemporaryNoteParser {

    private static final Pattern SUPPLIER = Pattern.compile("(?i)^dostawca\\s+(\\S+)");
    private static final Pattern NUMBER = Pattern.compile("(?i)^numer dostawy\\s*:\\s*(\\S+)");
    private static final Pattern DATE = Pattern.compile("(?i)^data\\s*:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern LINE_COUNT = Pattern.compile("(?i)^ilo.*linii\\s*:+\\s*(\\d+)");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public TemporaryNoteResponse parse(List<List<String>> sheet, String fileName) throws InvalidReportException {
        String supplierName = null;
        String supplierCode = null;
        String number = null;
        LocalDateTime createdAt = null;
        Columns columns = null;
        Integer declaredLines = null;
        List<TemporaryNoteResponse.Line> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        for (int r = 0; r < sheet.size(); r++) {
            List<String> cells = sheet.get(r);
            String first = cell(cells, 0);
            if (columns == null) {
                if (first == null) {
                    continue;
                }
                Columns header = Columns.of(cells);
                if (header != null) {
                    columns = header;
                    continue;
                }
                Matcher matcher;
                if ((matcher = SUPPLIER.matcher(first)).find()) {
                    supplierCode = matcher.group(1);
                } else if ((matcher = NUMBER.matcher(first)).find()) {
                    number = matcher.group(1);
                } else if ((matcher = DATE.matcher(first)).find()) {
                    try {
                        createdAt = LocalDateTime.parse(matcher.group(1) + " " + matcher.group(2), DATE_TIME);
                    } catch (DateTimeParseException e) {
                        // Data tylko opisuje bon: bez niej porównanie działa.
                    }
                } else if (supplierName == null) {
                    supplierName = first;
                }
                continue;
            }
            if (first == null && cells.stream().allMatch(value -> value == null || value.isBlank())) {
                continue;
            }
            Matcher count = first == null ? null : LINE_COUNT.matcher(first);
            if (count != null && count.find()) {
                declaredLines = Integer.parseInt(count.group(1));
                break;
            }
            try {
                lines.add(columns.line(cells));
            } catch (IllegalArgumentException e) {
                if (++errorCount <= BaseDeliveryParser.MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        if (columns == null) {
            throw new InvalidReportException("To nie jest tymczasowy bon dostawy z Mercalys: brak nagłówka z kolumnami "
                    + "Kod ITM8, EAN Prio, Ilość dost.");
        }
        if (errorCount > 0) {
            throw new InvalidReportException("Plik ma błędne linie", errors, errorCount);
        }
        if (declaredLines == null) {
            throw new InvalidReportException("Plik nie ma stopki „Ilość linii”: mógł zostać ucięty");
        }
        if (declaredLines != lines.size()) {
            throw new InvalidReportException("Plik ma " + lines.size() + " linii, a stopka mówi o " + declaredLines);
        }
        return new TemporaryNoteResponse(fileName, supplierCode, supplierName, number, createdAt, lines);
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size() || cells.get(index) == null) {
            return null;
        }
        String value = cells.get(index).strip();
        return value.isEmpty() ? null : value;
    }

    /** Położenie kolumn z wiersza nagłówka. */
    private record Columns(int itm8, int ean, int reference, int name, int vat, int quantity, int purchasePrice,
            int salePrice, int netValue) {

        /** Wiersz nagłówka albo {@code null}, gdy to nie on (brak którejś z kolumn). */
        static Columns of(List<String> cells) {
            int itm8 = -1, ean = -1, reference = -1, name = -1, vat = -1, quantity = -1, purchase = -1, sale = -1,
                    net = -1;
            for (int i = 0; i < cells.size(); i++) {
                String key = cells.get(i) == null ? "" : BaseDeliveryParser.key(cells.get(i));
                if (key.isEmpty()) {
                    continue;
                }
                if (key.contains("itm8")) {
                    itm8 = i;
                } else if (key.startsWith("ean")) {
                    ean = i;
                } else if (key.startsWith("referencja")) {
                    reference = i;
                } else if (key.startsWith("nazwa")) {
                    name = i;
                } else if (key.equals("vat")) {
                    vat = i;
                } else if (key.startsWith("ilo")) {
                    quantity = i;
                } else if (key.contains("cenazakupu")) {
                    purchase = i;
                } else if (key.startsWith("jednostkowac")) {
                    sale = i;
                } else if (key.contains("zakupunetto")) {
                    net = i;
                }
            }
            return itm8 < 0 || ean < 0 || name < 0 || quantity < 0 ? null
                    : new Columns(itm8, ean, reference, name, vat, quantity, purchase, sale, net);
        }

        TemporaryNoteResponse.Line line(List<String> cells) {
            String itm8 = code(cells, itm8());
            if (itm8 == null) {
                throw new IllegalArgumentException("brak kodu ITM8");
            }
            BigDecimal quantity = number(cells, quantity(), "Ilość dost.");
            if (quantity == null) {
                throw new IllegalArgumentException("brak ilości");
            }
            String name = cell(cells, name());
            return new TemporaryNoteResponse.Line(itm8, code(cells, ean()), code(cells, reference()),
                    name == null ? "" : name, number(cells, vat(), "VAT"), quantity,
                    number(cells, purchasePrice(), "Jednostk. cena zakupu"),
                    number(cells, salePrice(), "Jednostkowa C.S."),
                    number(cells, netValue(), "Wartość zakupu netto"));
        }

        /** Kod z cyfr (liczba z Excela bez „.0”); inny tekst zostaje, jak jest. */
        private static String code(List<String> cells, int index) {
            String value = cell(cells, index);
            return value == null ? null : value.replaceAll("^(\\d+)\\.0+$", "$1");
        }

        private static BigDecimal number(List<String> cells, int index, String label) {
            String value = cell(cells, index);
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value.replace(" ", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " „" + value + "” to nie liczba");
            }
        }
    }
}
