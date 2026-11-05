package com.intermanage.backend.dataentry.component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nazwa nowego artykułu od modelu: bez napisu o kaucji i bez marki, bo kaucja to osobna informacja przy pozycji, a marka
 * ma swoją kolumnę. Model mimo zasad potrafi je zostawić, więc sprawdzamy to tutaj.
 */
public final class ArticleName {

    /** KAUCJA, +KAUCJA, Z KAUCJA, KAUC, SYSTEM KAUCYJNY; KAUCZUK to nie kaucja. Grupa 1: BEZ KAUCJI. */
    private static final Pattern DEPOSIT = Pattern.compile(
            "(?:\\+\\s*)?(?<![A-Z0-9])(?:(BEZ)\\s+|(?:Z|SYSTEM)\\s+)?KAUC(?!Z)[A-Z]*(?![A-Z0-9])");

    private ArticleName() {
    }

    /** Czy nazwa z faktury mówi, że artykuł ma kaucję ("BEZ KAUCJI" się nie liczy). */
    public static boolean mentionsDeposit(String name) {
        if (name == null) {
            return false;
        }
        Matcher matcher = DEPOSIT.matcher(Measure.plain(name));
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nazwa jak z {@link Measure#normalizeName} bez kaucji i marki. Gdy z nazwy nie zostałoby nic poza miarą (np. sama
     * marka), dane słowa zostają.
     */
    public static String clean(String name, String brand, Measure measure) {
        String plain = Measure.plain(name == null ? "" : name);
        String withoutDeposit = DEPOSIT.matcher(plain).replaceAll(" ");
        String text = hasWords(withoutDeposit, measure) ? withoutDeposit : plain;
        String withoutBrand = withoutBrand(text, brand);
        return Measure.normalizeName(hasWords(withoutBrand, measure) ? withoutBrand : text, measure);
    }

    /** Marka jako całe słowa, bez względu na znaki między nimi (COCA-COLA i COCA COLA). */
    private static String withoutBrand(String text, String brand) {
        if (brand == null) {
            return text;
        }
        List<String> words = Arrays.stream(Measure.plain(brand).split("[^A-Z0-9]+")).filter(word -> !word.isEmpty())
                .map(Pattern::quote).toList();
        if (words.isEmpty()) {
            return text;
        }
        return Pattern.compile("(?<![A-Z0-9])" + String.join("[^A-Z0-9]*", words) + "(?![A-Z0-9])").matcher(text)
                .replaceAll(" ");
    }

    private static boolean hasWords(String name, Measure measure) {
        String normalized = Measure.normalizeName(name, measure);
        return !normalized.isEmpty() && !normalized.equals(measure.label());
    }
}
