package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Miara sprzedawanej sztuki: rodzaj sprzedaży ({@code kg}, {@code L}, {@code szt.}) i waga (WAGA z kartoteki: kg albo
 * litry w sztuce, przy {@code szt.} zawsze 1). Wielopak (np. 5×14 g) ma {@code packCount} > 1, a waga to suma.
 *
 * <p>Miara w nazwie: poniżej 1 kg gramy ({@code 250G}), od 1 kg kilogramy z przecinkiem ({@code 1,5KG}), poniżej 1 l
 * mililitry ({@code 330ML}), od 1 l litry ({@code 1,5L}), wielopak {@code 5X14G}; sztuki bez miary.
 */
public record Measure(String unit, BigDecimal netContent, int packCount) {

    public static final String KG = "kg";
    public static final String L = "L";
    public static final String PIECE = "szt.";

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";
    private static final String UNIT = "(KG|GR|G|ML|LTR|CL|L)(?![A-Z])";
    /** 5X14G, 6 x 1,5 L; liczba sztuk całkowita. */
    private static final Pattern PACK = Pattern.compile("(?<![\\d,])(\\d{1,3})\\s*[X×*]\\s*" + NUMBER + "\\s*" + UNIT);
    /** Liczba po "+" to dodatek z promocji ("100+50gr." = 50 sztuk gratis), nie miara. */
    private static final Pattern SIZE = Pattern.compile("(?<![\\d,+])" + NUMBER + "\\s*" + UNIT);
    private static final Set<String> KG_INVOICE_UNITS = Set.of("kg", "kg.", "kilogram");
    /** Opis kartonu przy mierze z faktury ("8/360 G", "150G/12", "/20SZT/") - w nazwie dla klienta zbędny. */
    private static final Pattern CARTON = Pattern.compile("(?<![\\d,])\\d+\\s*/(?=\\s*\\d)|/\\s*\\d*\\s*(SZT)?\\s*/?");

    public Measure {
        if (!KG.equals(unit) && !L.equals(unit) && !PIECE.equals(unit)) {
            throw new IllegalArgumentException("Nieznany rodzaj sprzedaży: " + unit);
        }
        netContent = netContent.setScale(3, RoundingMode.HALF_UP);
        packCount = Math.max(packCount, 1);
    }

    public static Measure piece() {
        return new Measure(PIECE, BigDecimal.ONE, 1);
    }

    /**
     * Miara z nazwy (z faktury albo z kartoteki); {@code null}, gdy nazwa nie ma gramatury ani pojemności. Przy kilku
     * miarach liczy się wielopak, a bez niego ostatnia miara w nazwie.
     */
    public static Measure parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String text = plain(name);
        Measure found = null;
        Matcher pack = PACK.matcher(text);
        while (pack.find()) {
            Measure item = parsed(pack.group(2), pack.group(3));
            int count = Integer.parseInt(pack.group(1));
            if (item != null && count > 1) {
                found = new Measure(item.unit, item.netContent.multiply(BigDecimal.valueOf(count)), count);
            }
        }
        if (found != null) {
            return found;
        }
        Matcher size = SIZE.matcher(text);
        while (size.find()) {
            Measure item = parsed(size.group(1), size.group(2));
            if (item != null) {
                found = item;
            }
        }
        return found;
    }

    /**
     * Miara nowego artykułu z pozycji faktury: towar fakturowany w kg bez miary w nazwie albo z miarą od 1 kg (mięso,
     * ser w kręgu) sprzedaje się na wagę - kg i waga 1; inaczej miara z nazwy ({@code null}, gdy jej nie ma).
     */
    public static Measure fromInvoice(String name, String invoiceUnit) {
        Measure parsed = parse(name);
        boolean perKg = invoiceUnit != null && KG_INVOICE_UNITS.contains(invoiceUnit.strip().toLowerCase(Locale.ROOT));
        if (perKg && (parsed == null || (KG.equals(parsed.unit) && parsed.netContent.compareTo(BigDecimal.ONE) >= 0))) {
            return new Measure(KG, BigDecimal.ONE, 1);
        }
        return parsed;
    }

    /** Miara z rodzaju sprzedaży i wagi (z kartoteki albo od modelu); {@code null} przy nieznanym rodzaju. */
    public static Measure of(String unit, BigDecimal netContent) {
        String normalized = normalizeUnit(unit);
        if (normalized == null) {
            return null;
        }
        if (PIECE.equals(normalized) || netContent == null || netContent.signum() <= 0) {
            return PIECE.equals(normalized) ? piece() : null;
        }
        return new Measure(normalized, netContent, 1);
    }

    /** Jednostka z kartoteki albo od modelu jako rodzaj sprzedaży: kg (też artykuły wagowe A.Wa), L albo szt. */
    public static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        return switch (unit.strip().toLowerCase(Locale.ROOT)) {
            case "kg", "a.wa", "g" -> KG;
            case "l", "ml" -> L;
            default -> PIECE;
        };
    }

    /** Miara do nazwy: 250G, 1,5KG, 330ML, 1,5L, 5X14G; przy sztukach pusta. */
    public String label() {
        if (PIECE.equals(unit)) {
            return "";
        }
        BigDecimal item = netContent.divide(BigDecimal.valueOf(packCount), 6, RoundingMode.HALF_UP);
        String size = KG.equals(unit) ? size(item, "G", "KG") : size(item, "ML", "L");
        return packCount > 1 ? packCount + "X" + size : size;
    }

    /**
     * Nazwa dla klienta: wielkie litery bez polskich znaków, bez kropek (ułamek z przecinkiem), bez miar i opisu
     * kartonu z oryginału, z miarą na końcu. Nie skraca: limit 30 znaków sprawdza tabela.
     */
    public static String normalizeName(String name, Measure measure) {
        String text = plain(name == null ? "" : name);
        text = text.replaceAll("(\\d)\\.(\\d)", "$1,$2").replace('.', ' ');
        text = CARTON.matcher(text).replaceAll(" ");
        text = PACK.matcher(text).replaceAll(" ");
        text = SIZE.matcher(text).replaceAll(" ");
        text = text.replaceAll("\\s+", " ").strip().replaceAll("^[\\s,/\\-]+|[\\s,/\\-]+$", "");
        String label = measure != null ? measure.label() : "";
        return label.isEmpty() ? text : (text.isEmpty() ? label : text + " " + label);
    }

    /** Wielkie litery bez znaków diakrytycznych (Ł nie rozkłada się w NFD, więc osobno). */
    public static String plain(String text) {
        String upper = text.toUpperCase(Locale.ROOT).replace('Ł', 'L');
        return Normalizer.normalize(upper, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static Measure parsed(String number, String unit) {
        BigDecimal value = new BigDecimal(number.replace(',', '.'));
        BigDecimal content = switch (unit) {
            case "G", "GR", "ML" -> value.divide(THOUSAND, 6, RoundingMode.HALF_UP);
            case "CL" -> value.movePointLeft(2);
            default -> value;
        };
        if (content.setScale(3, RoundingMode.HALF_UP).signum() <= 0) {
            return null;
        }
        return new Measure(unit.equals("KG") || unit.equals("G") || unit.equals("GR") ? KG : L, content, 1);
    }

    private static String size(BigDecimal value, String small, String large) {
        if (value.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal units = value.multiply(THOUSAND).setScale(0, RoundingMode.HALF_UP);
            return units.max(BigDecimal.ONE).toPlainString() + small;
        }
        return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',') + large;
    }
}
