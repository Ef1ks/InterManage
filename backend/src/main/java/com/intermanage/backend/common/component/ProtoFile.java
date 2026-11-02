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
 * Pliki PROTO1-PROTO6 dla programu sklepu (PROTO6: zamówienie garmażu, przyjęcie ruchu międzymagazynowego; wszystkie:
 * protokoły z wprowadzania danych): w linii EAN i zaraz za nim ilość na 9 znakach, 6 cyfr całości i 3 po przecinku,
 * bez separatora (1 szt. = 000001000, 0,5 kg = 000000500), CRLF. Program sklepu sam wczytuje plik z folderu, do którego
 * go zapisujemy.
 */
public final class ProtoFile {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Najwięcej, ile mieści pole ilości: 6 cyfr całości i 3 po przecinku. */
    private static final BigDecimal MAX = new BigDecimal("999999.999");
    /** Ilość ujemna: minus zajmuje pierwszy znak pola, zostaje 8 cyfr (do sprawdzenia z plikiem z systemu sklepu). */
    private static final BigDecimal MAX_NEGATIVE = new BigDecimal("99999.999");

    public record Line(String ean, BigDecimal quantity) {
    }

    private ProtoFile() {
    }

    /**
     * {@code PROTOn_RRRRMMDD_HHMMSS_SKLEP_UZYTKOWNIK.Traite0}: sklep to numer sklepu (przy PROTO6 garmażu i przyjęcia:
     * sklep, od którego przyszło zamówienie albo towar), czas to chwila wygenerowania pliku (Europe/Warsaw).
     */
    public static String fileName(int number, String storeNumber, String username) {
        return "PROTO" + number + "_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + namePart(storeNumber) + "_"
                + loginPart(username) + ".Traite0";
    }

    /**
     * Część nazwy pliku dla programu sklepu: same litery i cyfry. Program dzieli nazwę po {@code _}, więc podkreślnik,
     * spacja czy {@code &} w loginie albo haśle dostawcy ({@code pdv_10023}, {@code DR LEKS}) psują nazwę i program
     * pliku nie wczytuje; reszta znaków jest usuwana ({@code pdv10023}, {@code DRLEKS}).
     */
    public static String namePart(String part) {
        return part == null ? "" : part.replaceAll("[^A-Za-z0-9]", "");
    }

    /** Login w nazwie pliku: {@link #namePart}, a gdy nic nie zostaje, {@code user}. */
    public static String loginPart(String username) {
        String login = namePart(username);
        return login.isEmpty() ? "user" : login;
    }

    /** Linie bez EAN-u albo z ilością 0 lub ujemną są pomijane. */
    public static byte[] content(List<Line> lines) {
        return content(lines, false);
    }

    /** Linie bez EAN-u albo z ilością 0 są pomijane, ujemne tylko przy {@code negatives = false}. */
    public static byte[] content(List<Line> lines, boolean negatives) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            BigDecimal quantity = line.quantity();
            if (line.ean() == null || line.ean().isBlank() || quantity == null || quantity.signum() == 0
                    || (quantity.signum() < 0 && !negatives)) {
                continue;
            }
            if (quantity.abs().compareTo(quantity.signum() < 0 ? MAX_NEGATIVE : MAX) > 0) {
                throw ApiException.badRequest("Ilość " + quantity.stripTrailingZeros().toPlainString()
                        + " nie mieści się w pliku PROTO");
            }
            long thousandths = quantity.abs().setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact();
            text.append(line.ean().trim())
                    .append(quantity.signum() < 0 ? String.format("-%08d", thousandths) : String.format("%09d", thousandths))
                    .append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
