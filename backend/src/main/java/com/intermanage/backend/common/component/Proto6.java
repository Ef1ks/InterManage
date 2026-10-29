package com.intermanage.backend.common.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.intermanage.backend.common.exception.ApiException;

/**
 * Plik PROTO6 dla programu sklepu (zamówienie garmażu, przyjęcie ruchu międzymagazynowego): w linii EAN i zaraz
 * za nim ilość na 9 cyfrach, 6 całości i 3 po przecinku, bez separatora (1 szt. = 000001000, 0,5 kg = 000000500),
 * CRLF. Program sklepu sam wczytuje go z folderu, do którego go zapisujemy.
 */
public final class Proto6 {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Najwięcej, ile mieści pole ilości: 6 cyfr całości i 3 po przecinku. */
    private static final BigDecimal MAX = new BigDecimal("999999.999");

    public record Line(String ean, BigDecimal quantity) {
    }

    private Proto6() {
    }

    /**
     * {@code PROTO6_RRRRMMDD_HHMMSS_POCHODZENIE_UZYTKOWNIK.Traite0}: pochodzenie to numer sklepu, od którego
     * przyszło zamówienie albo towar, czas to chwila wygenerowania pliku (Europe/Warsaw).
     */
    public static String fileName(String originStoreNumber, String username) {
        return "PROTO6_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + originStoreNumber + "_"
                + username.replaceAll("[^A-Za-z0-9._-]", "_") + ".Traite0";
    }

    /** Linie bez EAN-u albo z ilością 0 są pomijane. */
    public static byte[] content(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (line.ean() == null || line.ean().isBlank() || line.quantity().signum() <= 0) {
                continue;
            }
            if (line.quantity().compareTo(MAX) > 0) {
                throw ApiException.badRequest("Ilość " + line.quantity() + " nie mieści się w pliku PROTO6");
            }
            long thousandths = line.quantity().setScale(3, RoundingMode.HALF_UP).movePointRight(3)
                    .longValueExact();
            text.append(line.ean().trim()).append(String.format("%09d", thousandths)).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
