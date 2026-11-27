package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Plik inwentaryzacji dla programu sklepu ({@code INVENTAIRE_20260622_144353_IF0AF901_IF0AF901.Traite0}, wzór
 * z programu sklepu): w linii EAN na 13 znakach ({@link PriceControlFile#ean}) i zaraz za nim policzona ilość
 * w tysięcznych na 8 cyfrach ({@code 5903895642483} + {@code 00024000} = 24 szt., 0,5 kg = {@code 00000500}), CRLF.
 * W nazwie czas zapisu (Europe/Warsaw) i dwa razy login osoby, która zapisuje.
 */
public final class InventoryFile {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";
    /** Najwięcej, ile mieści 8 cyfr w tysięcznych. */
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("99999.999");

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record Line(String ean, BigDecimal quantity) {
    }

    private InventoryFile() {
    }

    public static String fileName(String username) {
        String login = ProtoFile.loginPart(username);
        return "INVENTAIRE_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + login + "_" + login + ".Traite0";
    }

    /** Inwentaryzacja bez jednej pozycji zostawiłaby zły stan, więc zły kod albo ilość odrzuca cały plik. */
    public static byte[] content(List<Line> lines) {
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Inwentaryzacja nie ma artykułów z EAN-em");
        }
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            String ean = PriceControlFile.ean(line.ean());
            if (ean == null) {
                throw ApiException.badRequest("Kod " + line.ean() + " nie jest EAN-em");
            }
            BigDecimal quantity = line.quantity();
            if (quantity == null || quantity.signum() < 0 || quantity.compareTo(MAX_QUANTITY) > 0) {
                throw ApiException.badRequest("Ilość w inwentaryzacji to od 0 do 99 999,999 (EAN " + ean + ")");
            }
            long thousandths = quantity.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
            text.append(ean).append(String.format("%08d", thousandths)).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
