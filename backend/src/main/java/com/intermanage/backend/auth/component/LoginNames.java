package com.intermanage.backend.auth.component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Login pracownika z imienia i nazwiska: pierwsza litera imienia i nazwisko, bez polskich znaków. */
public final class LoginNames {

    /** Zostawia miejsce na numer przy tym samym loginie (kolumna ma 50 znaków). */
    private static final int MAX_BASE_LENGTH = 46;
    private static final int MIN_LENGTH = 3;

    private LoginNames() {
    }

    /** Jan Kowalski → jkowalski, Łukasz Nowak-Żak → lnowakzak. Pusty, gdy wyszłoby mniej niż 3 znaki. */
    public static String base(String displayName) {
        List<String> words = Arrays.stream(plain(displayName).split("\\s+"))
                .map(word -> word.replaceAll("[^a-z0-9]", ""))
                .filter(word -> !word.isEmpty())
                .toList();
        if (words.isEmpty()) {
            return "";
        }
        String login = words.size() == 1 ? words.getFirst() : words.getFirst().charAt(0) + words.getLast();
        login = login.substring(0, Math.min(login.length(), MAX_BASE_LENGTH));
        return login.length() < MIN_LENGTH ? "" : login;
    }

    private static String plain(String text) {
        String lower = text.strip().toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
