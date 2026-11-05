package com.intermanage.backend.document.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

import com.intermanage.backend.common.component.Percent;

/**
 * Wydruk dokumentu magazynowego (PDF i Excel z tych samych danych): nagłówek, linie, a na dole rozbicie na nomenklaturę
 * (rejony i ich rodziny) i na stawki VAT. Kwoty są już w znaku wydruku: wydania mają w raporcie zakup z minusem, tu
 * z plusem. Linie bez ilości i bez kwot (np. niezwrócona prasa) są pomijane, a ich liczba trafia do {@code skippedLines}.
 */
public record DocumentPrint(Heading heading, List<Line> lines, int skippedLines, List<NomenclatureRow> nomenclature,
        List<VatRow> vatRates, Totals totals, String printedBy) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int REGION_LENGTH = 2;
    private static final int FAMILY_LENGTH = 4;

    /**
     * Nagłówek. {@code party} to druga strona dokumentu (dostawca, sklep docelowy, obdarowany; {@code null}, gdy jej
     * nie ma, a pusta nazwa to miejsce do wpisania ręcznie). {@code details}: dodatkowe pola (nr dok. dostawcy...),
     * {@code signatures}: podpisy, pierwszy z nazwiskiem osoby drukującej.
     */
    public record Heading(String title, String label, String number, LocalDate date, Party store, Party party,
            List<Detail> details, List<String> signatures) {
    }

    /** Strona dokumentu: etykieta, nazwa i kolejne wiersze (adres, NIP). */
    public record Party(String label, String name, List<String> lines) {
    }

    public record Detail(String label, String value) {
    }

    /** {@code note}: opis pod nazwą, np. wartości sprzed korekty. */
    public record Line(int lineNumber, String itm8, String ean, String name, String brand, String supplier,
            String nomenclatureCode, String unit, BigDecimal quantity, Short vatRate, BigDecimal purchasePrice,
            BigDecimal purchaseValue, BigDecimal salePrice, BigDecimal saleValueNet, BigDecimal vatValue,
            BigDecimal saleValueGross, String note) {
    }

    /** Wiersz rozbicia; {@code region}: rejon, inaczej jego rodzina. {@code share}: % zakupu dokumentu. */
    public record NomenclatureRow(boolean region, String code, String name, int lineCount, BigDecimal purchaseValue,
            BigDecimal saleValueNet, BigDecimal saleValueGross, BigDecimal share) {
    }

    /** VAT od zakupu liczony od sumy netto w stawce (jak na bonie z systemu sklepu); sprzedaż jest w liniach raportu. */
    public record VatRow(Short rate, BigDecimal purchaseNet, BigDecimal purchaseVat, BigDecimal saleNet,
            BigDecimal saleVat, BigDecimal saleGross) {
    }

    public record Totals(BigDecimal purchaseValue, BigDecimal purchaseVat, BigDecimal saleValueNet, BigDecimal saleVat,
            BigDecimal saleValueGross) {
    }

    /** {@code nomenclatureNames}: nazwy rejonów i rodzin po kodzie. */
    public static DocumentPrint of(Heading heading, List<Line> allLines, Map<String, String> nomenclatureNames,
            String printedBy) {
        List<Line> lines = allLines.stream().filter(line -> !isEmpty(line)).toList();
        List<VatRow> vatRates = vatRates(lines);
        BigDecimal purchase = sum(lines, Line::purchaseValue);
        Totals totals = new Totals(purchase,
                vatRates.stream().map(VatRow::purchaseVat).reduce(BigDecimal.ZERO, BigDecimal::add),
                sum(lines, Line::saleValueNet), sum(lines, Line::vatValue), sum(lines, Line::saleValueGross));
        return new DocumentPrint(heading, lines, allLines.size() - lines.size(),
                nomenclature(lines, nomenclatureNames, purchase), vatRates, totals, printedBy);
    }

    /** Kody rejonu i rodziny z kodu linii (podrodziny, np. 140102), do pobrania nazw. */
    public static List<String> parentCodes(String code) {
        if (code == null || code.length() < REGION_LENGTH) {
            return List.of();
        }
        return code.length() < FAMILY_LENGTH ? List.of(code.substring(0, REGION_LENGTH))
                : List.of(code.substring(0, REGION_LENGTH), code.substring(0, FAMILY_LENGTH));
    }

    private static boolean isEmpty(Line line) {
        return isZero(line.quantity()) && isZero(line.purchaseValue()) && isZero(line.saleValueGross());
    }

    private static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    /** Rejony po kodzie, pod każdym jego rodziny; linie bez nomenklatury na końcu. */
    private static List<NomenclatureRow> nomenclature(List<Line> lines, Map<String, String> names,
            BigDecimal purchase) {
        Map<String, List<Line>> byRegion = new TreeMap<>(Comparator.<String, Boolean>comparing(String::isEmpty)
                .thenComparing(Comparator.naturalOrder()));
        lines.forEach(line -> byRegion.computeIfAbsent(prefix(line, REGION_LENGTH), key -> new ArrayList<>()).add(line));
        // Przy kwotach różnych znaków (przeniesienia stanów, zamiany w korektach) udział w sumie nic nie mówi.
        boolean shares = byRegion.values().stream()
                .allMatch(region -> sum(region, Line::purchaseValue).signum() * purchase.signum() >= 0);

        List<NomenclatureRow> rows = new ArrayList<>();
        byRegion.forEach((code, regionLines) -> {
            String name = code.isEmpty() ? "Bez nomenklatury" : names.getOrDefault(code, "Rejon " + code);
            rows.add(row(true, code, name, regionLines, shares ? purchase : null));
            Map<String, List<Line>> byFamily = new TreeMap<>();
            regionLines.forEach(line -> byFamily
                    .computeIfAbsent(prefix(line, FAMILY_LENGTH), key -> new ArrayList<>()).add(line));
            byFamily.forEach((family, familyLines) -> {
                if (!family.isEmpty()) {
                    rows.add(row(false, family, names.getOrDefault(family, "Rodzina " + family), familyLines,
                            shares ? purchase : null));
                }
            });
        });
        return rows;
    }

    private static NomenclatureRow row(boolean region, String code, String name, List<Line> lines,
            BigDecimal purchase) {
        BigDecimal value = sum(lines, Line::purchaseValue);
        return new NomenclatureRow(region, code, name, lines.size(), value, sum(lines, Line::saleValueNet),
                sum(lines, Line::saleValueGross), purchase == null ? null : Percent.of(value, purchase));
    }

    private static String prefix(Line line, int length) {
        String code = line.nomenclatureCode();
        return code != null && code.length() >= length ? code.substring(0, length) : "";
    }

    /** Od najwyższej stawki, bez stawki na końcu. */
    private static List<VatRow> vatRates(List<Line> lines) {
        Map<Short, List<Line>> byRate = new TreeMap<>(Comparator.nullsLast(Comparator.<Short>reverseOrder()));
        lines.forEach(line -> byRate.computeIfAbsent(line.vatRate(), rate -> new ArrayList<>()).add(line));
        Map<Short, VatRow> rows = new LinkedHashMap<>();
        byRate.forEach((rate, rateLines) -> {
            BigDecimal net = sum(rateLines, Line::purchaseValue);
            BigDecimal vat = rate == null ? BigDecimal.ZERO
                    : net.multiply(BigDecimal.valueOf(rate)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            rows.put(rate, new VatRow(rate, net, vat, sum(rateLines, Line::saleValueNet),
                    sum(rateLines, Line::vatValue), sum(rateLines, Line::saleValueGross)));
        });
        return List.copyOf(rows.values());
    }

    private static BigDecimal sum(List<Line> lines, Function<Line, BigDecimal> value) {
        return lines.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
