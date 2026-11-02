package com.intermanage.backend.basedelivery.component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.intermanage.backend.basedelivery.dto.BaseDeliveryRow;
import com.intermanage.backend.common.exception.InvalidReportException;

/**
 * Plik dostawy z bazy (np. {@code 09516050082.xls}): w pierwszym wierszu nagłówek z nazwami kolumn (Sklep, Data_Bl,
 * Nr_Bl, NrNosnika, ITM8, Nazwa_Produktu, Marka, PAK, Kaucja, Data_DLV, Dni_dla_Sklepu, EAN, … Kod_MEA, Nr_Zam_ITM,
 * Coli_Do_Przyg, Coli_Przyg, C_Cesja, C_Publi, VAT, Kwota_VAT, Kwota_HT, Kwota_TTC, Rejon, Rodz, Pod_Rodz, Typ_Sprz,
 * Jed_Sprz, CN, GTU), niżej po linii na artykuł, bez stopki. Kolumny szukamy po nazwie, bez wielkości liter. Cały plik
 * to jeden bon jednego sklepu; błędna linia odrzuca plik.
 */
@Component
public class BaseDeliveryParser {

    static final int MAX_REPORTED_ERRORS = 20;

    private static final DateTimeFormatter POLISH_DATE = DateTimeFormatter.ofPattern("d.MM.yyyy");
    /** Dzień 0 dat Excela (liczba dni od 30.12.1899). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private static final List<String> REQUIRED = List.of("sklep", "data_bl", "nr_bl", "nrnosnika", "itm8",
            "nazwa_produktu", "pak", "ean", "spolka", "coli_przyg", "c_cesja", "vat", "kwota_ht");

    /** Odczytany bon: numer sklepu, numer i data bonu oraz linie w kolejności z pliku. */
    public record Parsed(String storeNumber, String number, LocalDate deliveryDate, List<BaseDeliveryRow> rows) {
    }

    public Parsed parse(List<List<String>> sheet) throws InvalidReportException {
        if (sheet.isEmpty()) {
            throw new InvalidReportException("Plik jest pusty");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = sheet.getFirst();
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i) != null && !header.get(i).isBlank()) {
                columns.putIfAbsent(key(header.get(i)), i);
            }
        }
        List<String> missing = REQUIRED.stream().filter(name -> !columns.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new InvalidReportException("To nie jest plik dostawy z bazy: brak kolumn "
                    + String.join(", ", missing.stream().map(BaseDeliveryParser::label).toList()));
        }

        List<String> errors = new ArrayList<>();
        int errorCount = 0;
        List<BaseDeliveryRow> rows = new ArrayList<>();
        String storeNumber = null;
        String number = null;
        LocalDate deliveryDate = null;
        for (int r = 1; r < sheet.size(); r++) {
            Line line = new Line(sheet.get(r), columns);
            if (line.blank()) {
                continue;
            }
            try {
                String rowStore = line.required("sklep");
                String rowNumber = line.required("nr_bl");
                LocalDate rowDate = date(line.required("data_bl"), "Data_Bl");
                if (storeNumber == null) {
                    storeNumber = rowStore;
                    number = rowNumber;
                    deliveryDate = rowDate;
                } else if (!storeNumber.equals(rowStore) || !number.equals(rowNumber) || !deliveryDate.equals(rowDate)) {
                    throw new IllegalArgumentException("inny sklep, numer albo data bonu niż w pierwszej linii");
                }
                rows.add(line.row());
            } catch (IllegalArgumentException e) {
                if (++errorCount <= MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        if (errorCount > 0) {
            throw new InvalidReportException("Plik ma błędne linie", errors, errorCount);
        }
        if (rows.isEmpty()) {
            throw new InvalidReportException("Plik nie ma żadnej linii dostawy");
        }
        return new Parsed(storeNumber, number, deliveryDate, rows);
    }

    /** Nazwa kolumny bez znaków diakrytycznych i wielkości liter ({@code Nazwa_Produktu} → {@code nazwa_produktu}). */
    static String key(String header) {
        return Normalizer.normalize(header.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String label(String key) {
        return switch (key) {
            case "data_bl" -> "Data_Bl";
            case "nr_bl" -> "Nr_Bl";
            case "nrnosnika" -> "NrNosnika";
            case "itm8" -> "ITM8";
            case "nazwa_produktu" -> "Nazwa_Produktu";
            case "pak" -> "PAK";
            case "ean" -> "EAN";
            case "coli_przyg" -> "Coli_Przyg";
            case "c_cesja" -> "C_Cesja";
            case "vat" -> "VAT";
            case "kwota_ht" -> "Kwota_HT";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1);
        };
    }

    static LocalDate date(String value, String column) {
        String text = value.strip();
        if (text.matches("\\d{1,6}(\\.0+)?")) {
            return EXCEL_EPOCH.plusDays(Long.parseLong(text.replaceAll("\\..*", "")));
        }
        try {
            return text.contains(".") ? LocalDate.parse(text, POLISH_DATE) : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " „" + text + "” to nie data");
        }
    }

    /** Jedna linia arkusza z dostępem do komórek po nazwie kolumny. */
    private static final class Line {

        private final List<String> cells;
        private final Map<String, Integer> columns;

        Line(List<String> cells, Map<String, Integer> columns) {
            this.cells = cells;
            this.columns = columns;
        }

        boolean blank() {
            return cells.stream().allMatch(cell -> cell == null || cell.isBlank());
        }

        /** Tekst komórki bez spacji na brzegach; pusta albo brak kolumny = {@code null}. */
        String text(String column) {
            Integer index = columns.get(column);
            if (index == null || index >= cells.size() || cells.get(index) == null) {
                return null;
            }
            String value = cells.get(index).strip();
            return value.isEmpty() ? null : value;
        }

        String required(String column) {
            String value = text(column);
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + label(column));
            }
            return value;
        }

        /** Kod bez pustych oznaczeń systemu bazy ({@code -----}, {@code 0000-00-00}). */
        String code(String column) {
            String value = text(column);
            return value == null || value.matches("[-\\s]+") ? null : value;
        }

        BigDecimal number(String column) {
            String value = text(column);
            if (value == null) {
                return null;
            }
            try {
                return new BigDecimal(value.replace(" ", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label(column) + " „" + value + "” to nie liczba");
            }
        }

        BigDecimal requiredNumber(String column) {
            BigDecimal value = number(column);
            if (value == null) {
                throw new IllegalArgumentException("brak wartości w kolumnie " + label(column));
            }
            return value;
        }

        /** Kod nomenklatury z dwóch cyfr ({@code 1} z liczby w Excelu = {@code 01}). */
        String level(String column) {
            String value = code(column);
            if (value == null) {
                return null;
            }
            String digits = value.replaceAll("\\.0+$", "");
            if (!digits.matches("\\d{1,2}")) {
                throw new IllegalArgumentException(label(column) + " „" + value + "” to nie dwucyfrowy kod");
            }
            return digits.length() == 1 ? "0" + digits : digits;
        }

        BaseDeliveryRow row() {
            String itm8 = required("itm8").replaceAll("\\.0+$", "");
            if (!itm8.matches("\\d{1,13}")) {
                throw new IllegalArgumentException("ITM8 „" + itm8 + "” to nie kod z cyfr");
            }
            if (itm8.length() < 8) {
                itm8 = "0".repeat(8 - itm8.length()) + itm8;
            }
            String ean = code("ean");
            if (ean != null) {
                ean = ean.replaceAll("\\.0+$", "");
                if (!ean.matches("\\d{1,14}")) {
                    throw new IllegalArgumentException("EAN „" + ean + "” to nie kod z cyfr");
                }
                if (ean.matches("0+")) {
                    ean = null;
                }
            }
            String bestBefore = code("data_dlv");
            BigDecimal days = number("dni_dla_sklepu");
            String deposit = code("kaucja");
            String unit = code("jed_sprz");
            return new BaseDeliveryRow(
                    required("nrnosnika"),
                    code("nr_zam_itm"),
                    itm8,
                    required("nazwa_produktu"),
                    code("marka"),
                    requiredNumber("pak"),
                    deposit == null ? null : deposit.toUpperCase(Locale.ROOT),
                    bestBefore == null || bestBefore.matches("0{4}-0{2}-0{2}") ? null : date(bestBefore, "Data_DLV"),
                    days == null ? null : days.intValue(),
                    ean,
                    code("pkwiu"),
                    required("spolka").replaceAll("\\.0+$", ""),
                    code("kod_mea"),
                    number("coli_do_przyg") != null ? number("coli_do_przyg") : requiredNumber("coli_przyg"),
                    requiredNumber("coli_przyg"),
                    requiredNumber("c_cesja"),
                    number("c_publi"),
                    requiredNumber("vat"),
                    number("kwota_vat") != null ? number("kwota_vat") : BigDecimal.ZERO,
                    requiredNumber("kwota_ht"),
                    number("kwota_ttc") != null ? number("kwota_ttc") : requiredNumber("kwota_ht"),
                    level("rejon"),
                    level("rodz"),
                    level("pod_rodz"),
                    number("typ_sprz"),
                    unit == null ? null : unit.toUpperCase(Locale.ROOT),
                    code("cn"),
                    code("gtu"));
        }
    }
}
