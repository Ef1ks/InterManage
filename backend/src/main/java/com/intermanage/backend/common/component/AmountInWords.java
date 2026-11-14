package com.intermanage.backend.common.component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kwota słownie po polsku, jak na dokumentach kasowych i przelewach: {@code 123,45} → „sto dwadzieścia trzy złote
 * 45/100”. Grosze zostają cyframi.
 */
public final class AmountInWords {

    private static final String[] UNITS = { "", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem",
            "dziewięć" };
    private static final String[] TEENS = { "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście",
            "piętnaście", "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście" };
    private static final String[] TENS = { "", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt",
            "sześćdziesiąt", "siedemdziesiąt", "osiemdziesiąt", "dziewięćdziesiąt" };
    private static final String[] HUNDREDS = { "", "sto", "dwieście", "trzysta", "czterysta", "pięćset", "sześćset",
            "siedemset", "osiemset", "dziewięćset" };
    /** Rzędy: jednostki, tysiące, miliony, miliardy (liczba pojedyncza, 2-4, reszta). */
    private static final String[][] SCALES = { { "", "", "" }, { "tysiąc", "tysiące", "tysięcy" },
            { "milion", "miliony", "milionów" }, { "miliard", "miliardy", "miliardów" } };

    private AmountInWords() {
    }

    public static String of(BigDecimal amount) {
        BigDecimal rounded = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long zloty = rounded.longValue();
        int grosze = rounded.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = zloty == 0 ? "zero" : number(zloty);
        String currency = form(zloty, "złoty", "złote", "złotych");
        return (amount.signum() < 0 ? "minus " : "") + words + " " + currency + " " + String.format("%02d", grosze)
                + "/100";
    }

    static String number(long value) {
        StringBuilder result = new StringBuilder();
        int scale = 0;
        while (value > 0) {
            int group = (int) (value % 1000);
            if (group > 0) {
                String part;
                if (scale > 0 && group == 1) {
                    part = SCALES[scale][0];
                } else {
                    part = hundreds(group) + (scale > 0 ? " " + form(group, SCALES[scale][0], SCALES[scale][1],
                            SCALES[scale][2]) : "");
                }
                result.insert(0, result.isEmpty() ? part : part + " ");
            }
            value /= 1000;
            scale++;
        }
        return result.toString().strip();
    }

    private static String hundreds(int value) {
        StringBuilder words = new StringBuilder(HUNDREDS[value / 100]);
        int rest = value % 100;
        if (rest >= 10 && rest < 20) {
            append(words, TEENS[rest - 10]);
        } else {
            append(words, TENS[rest / 10]);
            append(words, UNITS[rest % 10]);
        }
        return words.toString();
    }

    private static void append(StringBuilder words, String word) {
        if (!word.isEmpty()) {
            if (!words.isEmpty()) {
                words.append(' ');
            }
            words.append(word);
        }
    }

    /** Odmiana po liczebniku: 1 → pierwsza forma, końcówka 2-4 (bez 12-14) → druga, reszta → trzecia. */
    private static String form(long value, String one, String few, String many) {
        if (value == 1) {
            return one;
        }
        long lastTwo = value % 100;
        long last = value % 10;
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
            return few;
        }
        return many;
    }
}
