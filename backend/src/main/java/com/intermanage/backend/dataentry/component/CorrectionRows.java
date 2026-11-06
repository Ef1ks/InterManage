package com.intermanage.backend.dataentry.component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.intermanage.backend.ksef.component.KsefInvoiceReader;

/**
 * Pary wierszy korekty: pozycja po korekcie i jej wiersz "przed korektą" ({@code StanPrzed}). Większość dostawców daje
 * im ten sam numer, ale np. Kuchnia Polki numeruje wiersze po kolei, więc wtedy para idzie po GTIN-ie, indeksie albo
 * nazwie. Pozycja bez pary w korekcie z takimi wierszami to artykuł, którego korekta nie zmienia (dostawcy powtarzają
 * całą fakturę, a wiersz "przed" dają tylko zmienionym).
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

    private static void match(List<KsefInvoiceReader.LineCodes> lines,
            Function<KsefInvoiceReader.LineCodes, String> key, Map<Integer, Integer> pairs, Set<Integer> used) {
        for (int after = 0; after < lines.size(); after++) {
            String value = key.apply(lines.get(after));
            if (lines.get(after).beforeCorrection() || pairs.containsKey(after) || value == null) {
                continue;
            }
            for (int before = 0; before < lines.size(); before++) {
                if (lines.get(before).beforeCorrection() && !used.contains(before)
                        && Objects.equals(value, key.apply(lines.get(before)))) {
                    pairs.put(after, before);
                    used.add(before);
                    break;
                }
            }
        }
    }
}
