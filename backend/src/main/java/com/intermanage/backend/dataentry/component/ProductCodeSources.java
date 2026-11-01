package com.intermanage.backend.dataentry.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intermanage.backend.ksef.component.KsefInvoiceReader;

/**
 * Skąd wziąć EAN pozycji faktury. Dostawcy podają go w różnych miejscach: zwykle w {@code GTIN}, czasem w
 * {@code Indeks}, we wpisie {@code DodatkowyOpis} ("EAN", "Kod kreskowy") albo w nazwie. Źródło {@link #AUTO} bierze dla
 * każdej pozycji pierwszy kod, który jest w kartotece sklepu, a bez trafienia GTIN.
 *
 * <p>Z pól innych niż GTIN bierzemy ciąg 8-14 cyfr tylko, gdy jest w kartotece albo ma poprawną cyfrę kontrolną GTIN,
 * bo indeks dostawcy bywa liczbą, która tylko wygląda jak EAN.
 */
public final class ProductCodeSources {

    public static final String AUTO = "AUTO";
    public static final String GTIN = "GTIN";
    public static final String INDEX = "INDEX";
    public static final String NAME = "NAME";
    public static final String EXTRA = "EXTRA:";

    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)\\d{8,14}(?!\\d)");

    private ProductCodeSources() {
    }

    /** Źródło do wyboru: {@code filled} pozycji ma w nim kod, {@code matched} z nich jest w kartotece. */
    public record Source(String key, String label, int filled, int matched) {
    }

    public static String label(String key) {
        return switch (key) {
            case AUTO -> "Automatycznie";
            case GTIN -> "GTIN (pole EAN)";
            case INDEX -> "Indeks dostawcy";
            case NAME -> "Kod w nazwie";
            default -> "Dodatkowy opis: " + key.substring(EXTRA.length());
        };
    }

    /** Źródła z pozycji faktury: GTIN, Indeks, klucze dodatkowego opisu, nazwa (w tej kolejności, bez AUTO). */
    public static List<String> keys(List<KsefInvoiceReader.LineCodes> lines) {
        Set<String> keys = new LinkedHashSet<>(List.of(GTIN, INDEX));
        lines.forEach(line -> line.extras().keySet().forEach(key -> keys.add(EXTRA + key)));
        keys.add(NAME);
        return List.copyOf(keys);
    }

    /** Ciągi cyfr, które w tym źródle mogą być EAN-em (GTIN w całości, w innych polach każdy ciąg 8-14 cyfr). */
    public static List<String> candidates(KsefInvoiceReader.LineCodes line, String key) {
        String value = switch (key) {
            case GTIN -> line.gtin();
            case INDEX -> line.index();
            case NAME -> line.name();
            default -> key.startsWith(EXTRA) ? line.extras().get(key.substring(EXTRA.length())) : null;
        };
        if (value == null || value.isBlank()) {
            return List.of();
        }
        if (GTIN.equals(key)) {
            String digits = value.replaceAll("\\D", "");
            return digits.length() >= 8 && digits.length() <= 14 ? List.of(digits) : List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * EAN pozycji z danego źródła: z GTIN-u zawsze, z innych pól kod z kartoteki albo z poprawną cyfrą kontrolną;
     * {@code null}, gdy źródło go nie ma. {@code known} mówi, czy kod (bez zer wiodących) jest w kartotece.
     */
    public static String code(KsefInvoiceReader.LineCodes line, String key, Predicate<String> known) {
        List<String> candidates = candidates(line, key);
        for (String candidate : candidates) {
            if (known.test(candidate)) {
                return candidate;
            }
        }
        if (GTIN.equals(key)) {
            return candidates.isEmpty() ? null : candidates.getFirst();
        }
        return candidates.stream().filter(ProductCodeSources::validGtin).findFirst().orElse(null);
    }

    /** Wynik dla pozycji przy wybranym źródle: kod i źródło, z którego pochodzi (przy AUTO różne dla pozycji). */
    public record Pick(String code, String source) {
    }

    public static Pick pick(KsefInvoiceReader.LineCodes line, String key, List<String> keys, Predicate<String> known) {
        if (!AUTO.equals(key)) {
            String code = code(line, key, known);
            return new Pick(code, code != null ? key : null);
        }
        for (String source : keys) {
            String code = code(line, source, known);
            if (code != null && known.test(code)) {
                return new Pick(code, source);
            }
        }
        for (String source : keys) {
            String code = code(line, source, known);
            if (code != null) {
                return new Pick(code, source);
            }
        }
        return new Pick(null, null);
    }

    /**
     * Identyfikator pozycji bez EAN-u, do kolumny EAN zamiast pustej komórki: pierwszy niepusty kod z faktury, najpierw
     * z wybranego źródła (kod własny dostawcy, np. indeks piekarni "175C"), potem w kolejności źródeł (GTIN, Indeks,
     * dodatkowe opisy), a bez żadnego nazwa. Wartość jak na fakturze, bez sprawdzania, czy to EAN; {@code source} to
     * opis pola dla człowieka. {@code null} przy pozycji bez kodu i nazwy.
     */
    public static Pick fallback(KsefInvoiceReader.LineCodes line, List<String> keys, String chosen) {
        String own = raw(line, chosen);
        if (own != null) {
            return new Pick(own, label(chosen));
        }
        for (String key : keys) {
            String value = raw(line, key);
            if (value != null) {
                return new Pick(value, label(key));
            }
        }
        return line.name() == null || line.name().isBlank() ? null : new Pick(line.name().strip(), "Nazwa z faktury");
    }

    /**
     * Wszystkie kody pozycji z faktury tak, jak są (GTIN, Indeks, dodatkowe opisy, bez nazwy): po nich szukamy
     * przypięcia do artykułu, więc kod przypięty przy jednym źródle działa też przy innym i przy AUTO.
     */
    public static List<String> rawCodes(KsefInvoiceReader.LineCodes line, List<String> keys) {
        return keys.stream().map(key -> raw(line, key)).filter(Objects::nonNull).distinct().toList();
    }

    /** Wartość pola źródła jak na fakturze (bez nazwy i AUTO); {@code null}, gdy pusta. */
    private static String raw(KsefInvoiceReader.LineCodes line, String key) {
        String value = switch (key) {
            case GTIN -> line.gtin();
            case INDEX -> line.index();
            case AUTO, NAME -> null;
            default -> key.startsWith(EXTRA) ? line.extras().get(key.substring(EXTRA.length())) : null;
        };
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Statystyka źródeł do okna wyboru: AUTO i źródła, w których jest choć jeden kod. W polach kodów {@code filled}
     * liczy każdą niepustą wartość, także kod własny dostawcy (indeks "114B"), żeby dało się wybrać takie pole; w
     * nazwie tylko ciągi cyfr, które mogą być EAN-em.
     */
    public static List<Source> sources(List<KsefInvoiceReader.LineCodes> lines, Predicate<String> known) {
        List<String> keys = keys(lines);
        Map<String, int[]> counts = new LinkedHashMap<>();
        counts.put(AUTO, new int[2]);
        keys.forEach(key -> counts.put(key, new int[2]));
        for (KsefInvoiceReader.LineCodes line : lines) {
            for (Map.Entry<String, int[]> entry : counts.entrySet()) {
                Pick pick = pick(line, entry.getKey(), keys, known);
                boolean ownCode = !AUTO.equals(entry.getKey()) && raw(line, entry.getKey()) != null;
                if (pick.code() != null || ownCode) {
                    entry.getValue()[0]++;
                }
                if (pick.code() != null && known.test(pick.code())) {
                    entry.getValue()[1]++;
                }
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> AUTO.equals(entry.getKey()) || entry.getValue()[0] > 0)
                .map(entry -> new Source(entry.getKey(), label(entry.getKey()), entry.getValue()[0],
                        entry.getValue()[1]))
                .toList();
    }

    /** Cyfra kontrolna GTIN (EAN-8, UPC-12, EAN-13, GTIN-14). */
    public static boolean validGtin(String code) {
        if (code == null || !code.matches("\\d{8}|\\d{12,14}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < code.length() - 1; i++) {
            int digit = code.charAt(code.length() - 2 - i) - '0';
            sum += i % 2 == 0 ? digit * 3 : digit;
        }
        return (10 - sum % 10) % 10 == code.charAt(code.length() - 1) - '0';
    }
}
