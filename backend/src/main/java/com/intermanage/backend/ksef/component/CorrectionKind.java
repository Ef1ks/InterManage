package com.intermanage.backend.ksef.component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Czy korekta zmienia ilości (zwrot, brak, zamiana artykułu), czy tylko kwoty (obniżka ceny, rabat): do wprowadzenia
 * w sklepie są tylko te pierwsze. Dwie formy zapisu korekt (jak w {@link CorrectionRows}):
 * <ul>
 * <li>z wierszami "przed korektą" ({@code StanPrzed}): suma ilości artykułu po korekcie inna niż przed albo zamiana
 * artykułu pod tym samym numerem. Suma, a nie para wierszy, bo baza SCA PR przy rozliczeniu megapromocji rozbija
 * wiersz na dwa (180 kg przed, po korekcie 88,61 kg w starej cenie i 91,39 kg w nowej) - to sama zmiana ceny.
 * Artykuł bez wiersza "przed" korekta tylko powtarza (dostawcy powtarzają całą fakturę).</li>
 * <li>bez nich pozycja to różnica: ilość różna od zera przy nieujemnej cenie to zmiana ilości, a ilość przy cenie na
 * minusie to obniżka ceny (np. Gniezno), ilość 0 to sama kwota.</li>
 * </ul>
 * Kaucje się nie liczą (zwrot opakowań nie zmienia towaru).
 */
public final class CorrectionKind {

    /**
     * Wersja reguły zapisywana przy korekcie ({@code quantity_change_rule}); po każdej zmianie reguły podnieść, żeby
     * korekty z XML w bazie przeliczyły się w tle. 2: suma ilości artykułu zamiast pary wierszy (megapromocje bazy).
     */
    public static final short RULE = 2;

    private CorrectionKind() {
    }

    /**
     * {@code lines} i {@code codes} to te same wiersze faktury w tej samej kolejności ({@link KsefInvoiceReader#read}
     * i {@link KsefInvoiceReader#lineCodes}).
     */
    public static boolean changesQuantity(List<KsefInvoiceReader.Line> lines, List<KsefInvoiceReader.LineCodes> codes) {
        if (lines.stream().anyMatch(KsefInvoiceReader.Line::beforeCorrection)) {
            Set<Integer> added = CorrectionRows.added(codes, CorrectionRows.pairs(codes));
            Map<String, BigDecimal> before = new HashMap<>();
            Map<String, BigDecimal> after = new HashMap<>();
            for (int index = 0; index < lines.size(); index++) {
                KsefInvoiceReader.Line line = lines.get(index);
                if (line.deposit()) {
                    continue;
                }
                if (added.contains(index)) {
                    return true;
                }
                BigDecimal quantity = line.quantity() != null ? line.quantity() : BigDecimal.ZERO;
                (line.beforeCorrection() ? before : after).merge(article(codes.get(index)), quantity, BigDecimal::add);
            }
            // Artykuł bez wiersza "przed" korekta tylko powtarza z faktury, więc liczą się artykuły z wierszem "przed".
            for (Map.Entry<String, BigDecimal> entry : before.entrySet()) {
                if (!same(entry.getValue(), after.get(entry.getKey()))) {
                    return true;
                }
            }
            return false;
        }
        return lines.stream().filter(line -> !line.deposit()).anyMatch(CorrectionKind::quantityDifference);
    }

    /** Różnica ilości: ilość różna od zera przy cenie nieujemnej (bez ceny: wartość tego samego znaku co ilość). */
    private static boolean quantityDifference(KsefInvoiceReader.Line line) {
        if (line.quantity() == null || line.quantity().signum() == 0) {
            return false;
        }
        if (line.unitNet() != null) {
            return line.unitNet().signum() >= 0;
        }
        return line.net() == null || line.net().signum() == 0 || line.net().signum() == line.quantity().signum();
    }

    /** Artykuł wiersza: indeks dostawcy, bez niego GTIN, na końcu nazwa. */
    private static String article(KsefInvoiceReader.LineCodes code) {
        if (code.index() != null && !code.index().isBlank()) {
            return "index:" + code.index().strip();
        }
        if (code.gtin() != null && !code.gtin().isBlank()) {
            return "gtin:" + code.gtin().strip();
        }
        String name = code.name() == null ? "" : code.name().strip().replaceAll("\\s+", " ");
        return "name:" + name.toUpperCase(Locale.ROOT);
    }

    private static boolean same(BigDecimal first, BigDecimal second) {
        BigDecimal a = first != null ? first : BigDecimal.ZERO;
        BigDecimal b = second != null ? second : BigDecimal.ZERO;
        return a.compareTo(b) == 0;
    }
}
