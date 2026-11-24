package com.intermanage.backend.cadtop.component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Wspólne odczyty komórek plików cadencier z centrali. */
final class CadSheets {

    private CadSheets() {
    }

    /** Nagłówek kolumny do porównania: małe litery i cyfry, bez znaków diakrytycznych i odstępów. */
    static String key(String header) {
        return header == null ? "" : Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String text(List<String> cells, int index) {
        if (index < 0 || index >= cells.size() || cells.get(index) == null) {
            return null;
        }
        String value = cells.get(index).replace(' ', ' ').strip();
        return value.isEmpty() ? null : value;
    }

    /** Numer cadencier ({@code 45}, {@code 45_1}); Excel potrafi oddać liczbę jako {@code 45.0}. */
    static String cadNumber(String value) {
        String number = value.replaceAll("\\.0+$", "");
        if (!number.matches("\\d{1,4}(_\\d{1,3})?")) {
            throw new IllegalArgumentException("nr_cad „" + value + "” to nie numer cadencier (np. 45 albo 45_1)");
        }
        return number;
    }

    static String shorten(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
