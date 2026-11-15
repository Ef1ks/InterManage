package com.intermanage.backend.mea.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Formularz MEA z portalu Grupy Muszkieterów (pierwszy arkusz pliku {@code data/mea/xls/<3 cyfry>/<nr>.xls}, np.
 * „MEA81534”), jak {@code AnalizujXLSMEA} w eMEA: wiersz z numerem MEA i nagłówkiem „Data rozpoczęcia promocji”,
 * pod nim tytuł, okres promocji i rezerwacji (daty jako numery dni Excela), dalej nagłówek pozycji od „Data rozrzutu
 * 1” (kolumny po nazwie: Kod Itm8, Kod Itm8 równoległy, EAN, Itm8 Opis, Itm8 Marka, Ilość opakowań na palecie, Ilość
 * sztuk w opakowaniu, Stawka VAT (ułamek), ceny i marże MEA i „Itm8 równoległy” (CAD), Gwarantowana ilość dni terminu,
 * Uwagi). Daty rozrzutów są przy każdej pozycji, bierzemy je z pierwszej (jak eMEA). Pozycje po kodzie MEA, Lp. od 1.
 */
@Component
public class MeaFormParser {

    /** Rozrzutów jest w pliku do 8, ale zamówienie do ATHOS (rekord 45 znaków) ma miejsce tylko na 4. */
    public static final int MAX_DISPATCHES = 4;
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** Nieczytelny formularz; komunikat idzie do użytkownika. */
    public static class InvalidForm extends RuntimeException {
        public InvalidForm(String message) {
            super(message);
        }
    }

    public record Form(String number, String title, LocalDate promoFrom, LocalDate promoTo,
            LocalDate reservationFrom, LocalDate reservationTo, List<LocalDate> dispatches, List<Line> lines) {
    }

    public record Line(int lp, String meaItm8, String parallelItm8, String ean, String name, String brand,
            Integer packsPerPallet, Integer unitsPerPack, Integer vatPercent, BigDecimal purchasePrice,
            BigDecimal parallelPurchasePrice, BigDecimal shelfPrice, BigDecimal parallelShelfPrice, BigDecimal margin,
            BigDecimal parallelMargin, Integer guaranteedDays, String note) {
    }

    public Form parse(List<List<String>> rows) {
        int headerAt = -1;
        for (int r = 0; r < Math.min(rows.size(), 15); r++) {
            if (!blank(cell(rows.get(r), 0)) && "data rozpoczecia promocji".equals(key(cell(rows.get(r), 1)))) {
                headerAt = r;
                break;
            }
        }
        if (headerAt < 0 || headerAt + 1 >= rows.size()) {
            throw new InvalidForm("To nie jest formularz MEA (brak nagłówka „Data rozpoczęcia promocji”)");
        }
        String number = cell(rows.get(headerAt), 0).trim();
        List<String> period = rows.get(headerAt + 1);

        int columnsAt = -1;
        for (int r = headerAt + 2; r < rows.size(); r++) {
            if ("data rozrzutu 1".equals(key(cell(rows.get(r), 1)))) {
                columnsAt = r;
                break;
            }
        }
        if (columnsAt < 0) {
            throw new InvalidForm("Formularz MEA " + number + " nie ma tabeli pozycji");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(columnsAt);
        for (int c = 0; c < header.size(); c++) {
            String name = key(header.get(c));
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, c);
            }
        }
        Integer itm8Column = columns.get("kod itm8");
        if (itm8Column == null) {
            throw new InvalidForm("Formularz MEA " + number + " nie ma kolumny „Kod Itm8”");
        }

        List<LocalDate> dispatches = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (int r = columnsAt + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String meaItm8 = digits(cell(row, itm8Column));
            if (meaItm8 == null) {
                continue;
            }
            if (lines.isEmpty()) {
                for (int d = 1; d <= MAX_DISPATCHES; d++) {
                    LocalDate date = date(text(row, columns, "data rozrzutu " + d));
                    if (date == null) {
                        break;
                    }
                    dispatches.add(date);
                }
            }
            lines.add(new Line(0, meaItm8,
                    digits(text(row, columns, "kod itm8 rownolegly")),
                    trimToNull(text(row, columns, "ean")),
                    trimToNull(text(row, columns, "itm8 opis")),
                    trimToNull(text(row, columns, "itm8 marka")),
                    integer(text(row, columns, "ilosc opakowan na palecie")),
                    integer(text(row, columns, "ilosc sztuk w opakowaniu")),
                    vat(text(row, columns, "stawka vat")),
                    money(text(row, columns, "cena zakupu netto")),
                    money(text(row, columns, "cena zakupu netto - itm8 rownolegly")),
                    money(text(row, columns, "cena polkowa")),
                    money(text(row, columns, "cena polkowa - itm8 rownolegly")),
                    money(text(row, columns, "marza sklepu")),
                    money(text(row, columns, "marza sklepu - itm8 rownolegly")),
                    integer(text(row, columns, "gwarantowana ilosc dni terminu")),
                    trimToNull(text(row, columns, "uwagi"))));
        }
        // eMEA układa pozycje po kodzie MEA (tekstowo, jak string.CompareTo) i numeruje je od nowa.
        lines.sort(Comparator.comparing(Line::meaItm8));
        List<Line> numbered = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            numbered.add(new Line(i + 1, line.meaItm8(), line.parallelItm8(), line.ean(), line.name(), line.brand(),
                    line.packsPerPallet(), line.unitsPerPack(), line.vatPercent(), line.purchasePrice(),
                    line.parallelPurchasePrice(), line.shelfPrice(), line.parallelShelfPrice(), line.margin(),
                    line.parallelMargin(), line.guaranteedDays(), line.note()));
        }
        return new Form(number, trimToNull(cell(period, 0)), date(cell(period, 1)), date(cell(period, 2)),
                date(cell(period, 3)), date(cell(period, 4)), dispatches, numbered);
    }

    private static String text(List<String> row, Map<String, Integer> columns, String name) {
        Integer column = columns.get(name);
        return column == null ? null : cell(row, column);
    }

    private static String cell(List<String> row, int column) {
        return column < row.size() ? row.get(column) : null;
    }

    /** Nagłówek bez polskich znaków, małymi literami, z pojedynczymi spacjami (plik ma je w windows-1250). */
    private static String key(String value) {
        if (value == null) {
            return "";
        }
        String plain = Normalizer.normalize(value.replace('ł', 'l').replace('Ł', 'L'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    /** Kod z samych cyfr; kod zapisany w Excelu jako liczba może przyjść z „.0” na końcu. */
    private static String digits(String value) {
        if (blank(value)) {
            return null;
        }
        String plain = value.replace(" ", "").trim();
        if (plain.endsWith(".0")) {
            plain = plain.substring(0, plain.length() - 2);
        }
        return plain.matches("\\d+") ? plain : null;
    }

    private static BigDecimal decimal(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Ceny i marże są w pliku jako float (6.03000020980835), więc zaokrąglamy do grosza. */
    private static BigDecimal money(String value) {
        BigDecimal number = decimal(value);
        return number == null ? null : number.setScale(2, RoundingMode.HALF_UP);
    }

    private static Integer integer(String value) {
        BigDecimal number = decimal(value);
        return number == null ? null : number.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Stawka jako ułamek (0.05) albo już w procentach. */
    private static Integer vat(String value) {
        BigDecimal number = decimal(value);
        if (number == null) {
            return null;
        }
        BigDecimal percent = number.compareTo(BigDecimal.ONE) < 0 ? number.movePointRight(2) : number;
        return percent.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Data jako numer dnia Excela albo tekst RRRR-MM-DD. */
    private static LocalDate date(String value) {
        if (blank(value)) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return LocalDate.parse(text.substring(0, 10));
        }
        BigDecimal serial = decimal(text);
        if (serial == null || serial.signum() <= 0) {
            return null;
        }
        return EXCEL_EPOCH.plusDays(serial.longValue());
    }
}
