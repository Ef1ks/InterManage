package com.intermanage.backend.dataentry.component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Plik etykiet cenowych dla programu sklepu ({@code ETIQUETTE_20261103_105800_ImpEk_ImpEk.traite0}; jak CTRLPRIX
 * program wczytuje tylko {@code .traite0}): w linii EAN na 13 znakach ({@link PriceControlFile#ean}) i zaraz za nim
 * liczba etykiet na 4 cyfrach ({@code 2405480000000} + {@code 0001}), CRLF. W nazwie czas zapisu (Europe/Warsaw)
 * i dwa razy login osoby, która zapisuje, jak w pliku z programu sklepu.
 */
public final class LabelFile {

    public static final String CONTENT_TYPE = "text/plain;charset=US-ASCII";
    public static final int MAX_COUNT = 9999;

    private static final ZoneId STORE_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public record Line(String ean, int count) {
    }

    private LabelFile() {
    }

    public static String fileName(String username) {
        String login = ProtoFile.loginPart(username);
        return "ETIQUETTE_" + ZonedDateTime.now(STORE_ZONE).format(TIME) + "_" + login + "_" + login + ".traite0";
    }

    /** Linie bez EAN-u albo z liczbą etykiet poniżej 1 są pomijane. */
    public static byte[] content(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            String ean = PriceControlFile.ean(line.ean());
            if (ean == null || line.count() < 1) {
                continue;
            }
            if (line.count() > MAX_COUNT) {
                throw ApiException.badRequest("Najwyżej " + MAX_COUNT + " etykiet jednego artykułu");
            }
            text.append(ean).append(String.format("%04d", line.count())).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
