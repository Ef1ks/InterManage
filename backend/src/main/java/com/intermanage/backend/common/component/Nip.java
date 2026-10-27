package com.intermanage.backend.common.component;

import java.util.Map;

import com.intermanage.backend.common.exception.ApiException;

/** NIP firmy z formularza: sklepy i połączenia KSeF. */
public final class Nip {

    private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    private Nip() {
    }

    /** NIP bez myślników i spacji, z poprawną cyfrą kontrolną; inaczej 400 z błędem pola {@code field}. */
    public static String parse(String value, String field) {
        String digits = value == null ? "" : value.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{10}") || !checksumMatches(digits)) {
            throw ApiException.badRequest("Nieprawidłowy NIP").withProperty("errors", Map.of(field, "Nieprawidłowy NIP"));
        }
        return digits;
    }

    private static boolean checksumMatches(String digits) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * (digits.charAt(i) - '0');
        }
        return sum % 11 == digits.charAt(9) - '0';
    }
}
