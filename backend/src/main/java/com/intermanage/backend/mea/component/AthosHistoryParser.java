package com.intermanage.backend.mea.component;

import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Dziennik ATHOS ({@code \\<serwer SR>\appres\athos2000\Histo.Log}, windows-1250), z którego eMEA brało statusy MEA
 * ({@code Zamawiacz.OdczytPlikuHistoryLog}): linia z „envio de encomendas mea” to wysyłka zamówień MEA z datą od znaku
 * 58 ({@code dd/MM/yyyy?HH:mm:ss}, między datą a godziną dowolny znak), a pod nią pary linii wcięte (znaki 2-5 puste):
 * w pierwszej numer MEA od znaku 34 (5 cyfr), w drugiej liczba pozycji od znaku 23 (4 cyfry) i kartonów (COLI) od znaku
 * 41 (5 cyfr). Pozycje znaków są przeniesione z eMEA; datę szukamy też w całej linii, gdyby kolumna się przesunęła.
 */
@Component
public class AthosHistoryParser {

    public static final Charset CHARSET = Charset.forName("windows-1250");
    private static final String MARKER = "envio de encomendas mea";
    private static final Pattern DATE = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})\\D(\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern NUMBER = Pattern.compile("\\d{4,6}");

    /** Wysłane zamówienie MEA: kiedy (czas lokalny z dziennika), numer MEA, pozycje i kartony. */
    public record Send(LocalDateTime sentAt, String number, int positions, int colis) {
    }

    public List<Send> parse(byte[] file) {
        String[] lines = new String(file, CHARSET).split("\r?\n", -1);
        List<Send> sends = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].toLowerCase(Locale.ROOT).contains(MARKER)) {
                continue;
            }
            LocalDateTime sentAt = date(lines[i]);
            if (sentAt == null) {
                continue;
            }
            int at = i + 1;
            while (at + 1 < lines.length && indented(lines[at])) {
                String number = cut(lines[at], 34, 39);
                Integer positions = number(cut(lines[at + 1], 23, 27));
                Integer colis = number(cut(lines[at + 1], 41, 46));
                if (!NUMBER.matcher(number).matches() || positions == null || colis == null) {
                    break;
                }
                sends.add(new Send(sentAt, number, positions, colis));
                at += 2;
            }
            i = at - 1;
        }
        return sends;
    }

    /** Linia pozycji wysyłki: znaki 2-5 puste (wcięcie pod linią wysyłki). */
    private static boolean indented(String line) {
        return line.length() >= 6 && line.substring(2, 6).isBlank();
    }

    private static LocalDateTime date(String line) {
        Matcher fixed = DATE.matcher(cut(line, 58, 77));
        Matcher matcher = fixed.matches() ? fixed : DATE.matcher(line);
        if (!(matcher == fixed || matcher.find())) {
            return null;
        }
        try {
            return LocalDateTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(4)),
                    Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(6)));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private static String cut(String line, int from, int to) {
        if (line.length() <= from) {
            return "";
        }
        return line.substring(from, Math.min(to, line.length())).trim();
    }

    private static Integer number(String value) {
        try {
            return value.isEmpty() ? null : Integer.valueOf(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
