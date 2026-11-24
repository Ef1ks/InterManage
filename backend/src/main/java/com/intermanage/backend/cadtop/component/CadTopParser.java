package com.intermanage.backend.cadtop.component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.XlsxReader;
import com.intermanage.backend.common.exception.InvalidReportException;
import com.intermanage.backend.common.exception.InvalidWorkbookException;

/**
 * Plik TOP z portalu (np. {@code 261106_swadzim.xlsx} = „cad45_1_Swadzim - produkty z cad. oraz z oznaczeniem TOP”):
 * wszystkie artykuły cadencier rejonu, nagłówek w pierwszym wierszu, jeden numer cadencier (nr_cad) na cały plik.
 * Kolumny po nazwie bez znaków diakrytycznych i wielkości liter. Błędny wiersz odrzuca plik.
 */
@Component
public class CadTopParser {

    private static final int MAX_REPORTED_ERRORS = 20;

    public record Parsed(String nrCad, List<Article> articles) {
    }

    /** {@code topMark}: BTop, B, A albo puste; {@code marginPercent}: od sprzedaży brutto, z pliku. */
    public record Article(String itm8, String ean, String name, String brand, String supplierCode, String productType,
            BigDecimal purchasePrice, BigDecimal salePrice, Integer pack, BigDecimal vatPercent, String topMark,
            String subfamilyCode, String category, boolean isNew, BigDecimal marginPercent,
            BigDecimal networkRotation) {
    }

    private record Columns(int number, int itm8, int ean, int name, int brand, int supplier, int type, int purchase,
            int sale, int pack, int vat, int top, int nomenclature, int category, int isNew, int margin,
            int rotation) {

        static Columns of(List<String> keys) {
            return new Columns(keys.indexOf("nrcad"), keys.indexOf("itm8"), keys.indexOf("ean"),
                    keys.indexOf("opisproduktu"), keys.indexOf("marka"), keys.indexOf("koddostawcy"),
                    keys.indexOf("typproduktu"), keys.indexOf("cenazakupu"), keys.indexOf("cenasprzedazy"),
                    keys.indexOf("sztukwcolis"), keys.indexOf("stawkavat"), keys.indexOf("topy"),
                    keys.indexOf("kodnomenklatury"), keys.indexOf("kat"), startingWith(keys, "nowosci"),
                    startingWith(keys, "marza"), startingWith(keys, "regmov"));
        }

        /** Kolumny, bez których plik jest odrzucany: wszystkie, bo plan ich użycia dopiero powstaje. */
        List<String> missing() {
            List<String> missing = new ArrayList<>();
            check(missing, number, "nr_cad");
            check(missing, itm8, "itm8");
            check(missing, ean, "EAN");
            check(missing, name, "OPIS produktu");
            check(missing, brand, "MARKA");
            check(missing, supplier, "KOD DOSTAWCY");
            check(missing, type, "TYP PRODUKTU");
            check(missing, purchase, "CENA ZAKUPU");
            check(missing, sale, "CENA SPRZEDAŻY");
            check(missing, pack, "sztuk w colis");
            check(missing, vat, "stawka VAT");
            check(missing, top, "TOPY");
            check(missing, nomenclature, "KOD NOMENKLATURY");
            check(missing, category, "kat");
            check(missing, isNew, "NOWOŚCI");
            check(missing, margin, "MARŻA");
            check(missing, rotation, "reg_mov_siec");
            return missing;
        }

        private static void check(List<String> missing, int index, String name) {
            if (index < 0) {
                missing.add(name);
            }
        }
    }

    private final XlsxReader xlsxReader;

    public CadTopParser(XlsxReader xlsxReader) {
        this.xlsxReader = xlsxReader;
    }

    public Parsed parse(Path file) throws IOException, InvalidWorkbookException, InvalidReportException {
        Reader reader = new Reader();
        xlsxReader.readFirstSheet(file, reader::row);
        return reader.result();
    }

    private static final class Reader {

        private Columns columns;
        private List<String> missing = List.of("nr_cad", "itm8", "TOPY");
        private String nrCad;
        private final List<Article> articles = new ArrayList<>();
        private final Set<String> itm8s = new HashSet<>();
        private final List<String> errors = new ArrayList<>();
        private int errorCount;

        void row(int rowNumber, List<String> cells) {
            if (columns == null) {
                if (rowNumber == 1) {
                    Columns found = Columns.of(cells.stream().map(CadSheets::key).toList());
                    missing = found.missing();
                    columns = missing.isEmpty() ? found : null;
                }
                return;
            }
            // Puste wiersze na końcu arkusza.
            if (cells.stream().allMatch(cell -> cell == null || cell.isBlank())) {
                return;
            }
            try {
                String number = CadSheets.cadNumber(required(cells, columns.number(), "nr_cad"));
                if (nrCad == null) {
                    nrCad = number;
                } else if (!nrCad.equals(number)) {
                    throw new IllegalArgumentException(
                            "inny numer cadencier (" + number + ") niż wyżej (" + nrCad + ")");
                }
                Article article = article(cells);
                if (!itm8s.add(article.itm8())) {
                    throw new IllegalArgumentException("ITM8 " + article.itm8() + " jest w pliku drugi raz");
                }
                articles.add(article);
            } catch (IllegalArgumentException e) {
                if (++errorCount <= MAX_REPORTED_ERRORS) {
                    errors.add("Wiersz " + rowNumber + ": " + e.getMessage());
                }
            }
        }

        private Article article(List<String> cells) {
            String itm8 = required(cells, columns.itm8(), "itm8").replaceAll("\\.0+$", "");
            if (!itm8.matches("\\d{1,13}")) {
                throw new IllegalArgumentException("itm8 „" + itm8 + "” to nie kod");
            }
            String ean = CadSheets.text(cells, columns.ean());
            if (ean != null) {
                ean = ean.replaceAll("\\.0+$", "");
                if (!ean.matches("\\d{1,14}")) {
                    throw new IllegalArgumentException("EAN „" + ean + "” to nie kod");
                }
            }
            String nomenclature = CadSheets.text(cells, columns.nomenclature());
            if (nomenclature != null) {
                nomenclature = nomenclature.replace(" ", "");
                if (!nomenclature.matches("\\d{2,6}")) {
                    throw new IllegalArgumentException("nomenklatura „" + nomenclature + "” to nie kod");
                }
            }
            BigDecimal pack = number(cells, columns.pack(), "sztuk w colis", 0);
            BigDecimal margin = number(cells, columns.margin(), "MARŻA", 4);
            return new Article(padded(itm8), ean == null ? null : padded(ean),
                    CadSheets.shorten(required(cells, columns.name(), "OPIS produktu"), 200),
                    CadSheets.shorten(CadSheets.text(cells, columns.brand()), 100),
                    CadSheets.shorten(CadSheets.text(cells, columns.supplier()), 20),
                    CadSheets.shorten(CadSheets.text(cells, columns.type()), 10),
                    number(cells, columns.purchase(), "CENA ZAKUPU", 4),
                    number(cells, columns.sale(), "CENA SPRZEDAŻY", 2), pack == null ? null : pack.intValue(),
                    number(cells, columns.vat(), "stawka VAT", 2),
                    CadSheets.shorten(CadSheets.text(cells, columns.top()), 10), nomenclature,
                    CadSheets.shorten(CadSheets.text(cells, columns.category()), 20),
                    CadSheets.text(cells, columns.isNew()) != null,
                    margin == null ? null : margin.movePointRight(2).setScale(2, RoundingMode.HALF_UP),
                    number(cells, columns.rotation(), "reg_mov_siec", 4));
        }

        Parsed result() throws InvalidReportException {
            if (columns == null) {
                throw new InvalidReportException("To nie jest plik TOP: brak kolumn " + String.join(", ", missing));
            }
            if (errorCount > 0) {
                throw new InvalidReportException("Plik TOP ma błędne wiersze", errors, errorCount);
            }
            if (articles.isEmpty()) {
                throw new InvalidReportException("Plik TOP nie ma żadnego artykułu");
            }
            return new Parsed(nrCad, articles);
        }
    }

    private static String padded(String code) {
        return "0".repeat(Math.max(0, 13 - code.length())) + code;
    }

    private static int startingWith(List<String> keys, String prefix) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String required(List<String> cells, int index, String column) {
        String value = CadSheets.text(cells, index);
        if (value == null) {
            throw new IllegalArgumentException("brak wartości w kolumnie " + column);
        }
        return value;
    }

    private static BigDecimal number(List<String> cells, int index, String column, int scale) {
        String value = CadSheets.text(cells, index);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(" ", "").replace(',', '.')).setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " „" + value + "” to nie liczba");
        }
    }
}
