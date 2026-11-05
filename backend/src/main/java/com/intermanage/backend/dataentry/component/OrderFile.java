package com.intermanage.backend.dataentry.component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Plik zamówienia dla programu sklepu ({@code COMMANDE_20261105_084900_ImpEk_ImpEk.traite0}; jak CTRLPRIX program
 * wczytuje tylko {@code .traite0}): w linii EAN na 13 znakach ({@link PriceControlFile#ean}) i zaraz za nim ilość na
 * 2 cyfrach ({@code 5908243692998} + {@code 01}), CRLF. W nazwie czas zapisu (Europe/Warsaw) i dwa razy login osoby,
 * która zapisuje, jak w pliku z programu sklepu.
 */
public final class OrderFile {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";
    public static final int MAX_QUANTITY = 99;

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record Line(String ean, int quantity) {
    }

    private OrderFile() {
    }

    public static String fileName(String username) {
        String login = ProtoFile.loginPart(username);
        return "COMMANDE_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + login + "_" + login + ".traite0";
    }

    /** Zamówienie bez jednej pozycji byłoby gorsze niż brak pliku, więc zły kod albo ilość odrzuca cały plik. */
    public static byte[] content(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            String ean = PriceControlFile.ean(line.ean());
            if (ean == null) {
                throw ApiException.badRequest("Kod " + line.ean() + " nie jest EAN-em");
            }
            if (line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
                throw ApiException.badRequest("Ilość w zamówieniu to od 1 do " + MAX_QUANTITY + " (EAN " + ean + ")");
            }
            text.append(ean).append(String.format("%02d", line.quantity())).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
