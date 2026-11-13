package com.intermanage.backend.ksef.component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;


/**
 * Pary wierszy korekty: pozycja po korekcie i jej wiersz "przed korektą" ({@code StanPrzed}). Większość dostawców daje
 * im ten sam numer, ale np. Kuchnia Polki numeruje wiersze po kolei, więc wtedy para idzie po GTIN-ie, indeksie albo
 * nazwie. Pozycja bez pary w korekcie z takimi wierszami to artykuł, którego korekta nie zmienia (dostawcy powtarzają
 * całą fakturę, a wiersz "przed" dają tylko zmienionym). Ten sam numer przy innym artykule to zamiana (Lukas: sałata
 * masłowa przed, lodowa po): artykuł "przed" odchodzi, a artykuł "po" dochodzi ({@link #added}).
 */
public final class CorrectionRows {

    private CorrectionRows() {
    }

    /** Pozycja po korekcie → jej wiersz "przed korektą" (indeksy na liście wierszy faktury). */
    public static Map<Integer, Integer> pairs(List<KsefInvoiceReader.LineCodes> lines) {
        Map<Integer, Integer> pairs = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        match(lines, KsefInvoiceReader.LineCodes::number, pairs, used);
        match(lines, KsefInvoiceReader.LineCodes::gtin, pairs, used);
        match(lines, KsefInvoiceReader.LineCodes::index, pairs, used);
        match(lines, KsefInvoiceReader.LineCodes::name, pairs, used);
        return pairs;
    }

    /** Pozycje po korekcie, które zastąpiły inny artykuł: bez pary, z wolnym wierszem "przed" o tym samym numerze. */
    public static Set<Integer> added(List<KsefInvoiceReader.LineCodes> lines, Map<Integer, Integer> pairs) {
        Set<Integer> added = new HashSet<>();
        for (int after = 0; after < lines.size(); after++) {
            String number = lines.get(after).number();
            if (lines.get(after).beforeCorrection() || pairs.containsKey(after) || number == null) {
                continue;
            }
            for (int before = 0; before < lines.size(); before++) {
                if (lines.get(before).beforeCorrection() && !pairs.containsValue(before)
                        && number.equals(lines.get(before).number())) {
                    added.add(after);
                    break;
                }
            }
        }
        return added;
    }

    private static void match(List<KsefInvoiceReader.LineCodes> lines,
            Function<KsefInvoiceReader.LineCodes, String> key, Map<Integer, Integer> pairs, Set<Integer> used) {
        for (int after = 0; after < lines.size(); after++) {
            String value = key.apply(lines.get(after));
            if (lines.get(after).beforeCorrection() || pairs.containsKey(after) || value == null) {
                continue;
            }
            for (int before = 0; before < lines.size(); before++) {
                if (lines.get(before).beforeCorrection() && !used.contains(before)
                        && Objects.equals(value, key.apply(lines.get(before)))
                        && sameArticle(lines.get(after), lines.get(before))) {
                    pairs.put(after, before);
                    used.add(before);
                    break;
                }
            }
        }
    }

    /** Po kodzie, który mają oba wiersze, a bez kodów po nazwie. */
    private static boolean sameArticle(KsefInvoiceReader.LineCodes first, KsefInvoiceReader.LineCodes second) {
        if (first.gtin() != null && second.gtin() != null) {
            return first.gtin().strip().equals(second.gtin().strip());
        }
        if (first.index() != null && second.index() != null) {
            return first.index().strip().equals(second.index().strip());
        }
        return name(first).equals(name(second));
    }

    private static String name(KsefInvoiceReader.LineCodes line) {
        return line.name() == null ? "" : line.name().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
