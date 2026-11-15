package com.intermanage.backend.mea.component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.StoreTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Klient REST portalu Grupy Muszkieterów (api.grupamuszkieterow.pl). Logowanie: token z {@code keyTMP = md5(md5(KEY) +
 * md5(yyyyMMdd))} przez {@code /authshop/}, ważny godzinę; potem listy (MEA, dokumenty BL) tym tokenem. KEY sklep bierze
 * z portalu „Moje konto -> API"; {@code appKEY} identyfikuje aplikację. SECRET nie bierze udziału w logowaniu.
 */
@Component
public class MurphysoftClient {

    /** Usługa niedostępna: brak/odrzucony klucz, błąd sieci albo serwera portalu. Komunikat idzie do użytkownika. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** Token dostępu z jego czasem wygaśnięcia (strefa sklepu). */
    public record Token(String value, Instant expiresAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(MurphysoftClient.class);
    private static final DateTimeFormatter STOP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient http;
    private final JsonMapper json;
    private final String baseUrl;
    private final String appKey;
    private final Duration timeout;

    public MurphysoftClient(JsonMapper json, @Value("${app.mea.base-url}") String baseUrl,
            @Value("${app.mea.app-key}") String appKey, @Value("${app.mea.timeout}") Duration timeout) {
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.appKey = appKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean configured() {
        return appKey != null && !appKey.isBlank();
    }

    /** Loguje sklep kluczem API i zwraca token dostępu. */
    public Token authorize(int shopId, String apiKey) {
        String ymd = LocalDateTime.now(StoreTime.ZONE).format(DateTimeFormatter.BASIC_ISO_DATE);
        String keyTmp = md5(md5(apiKey) + md5(ymd));
        JsonNode body = post("authshop/", Map.of(
                "appKEY", appKey,
                "shopID", Integer.toString(shopId),
                "keyTMP", keyTmp));
        String token = body.path("token").asString("");
        if (!"ok".equalsIgnoreCase(body.path("info").asString("")) || token.isBlank()) {
            throw new Unavailable("Portal nie wydał tokenu. Sprawdź klucz API sklepu (Moje konto -> API)");
        }
        return new Token(token, parseStop(body.path("stop").asString("")));
    }

    /** Lista MEA sklepu tym tokenem; {@code day} to ile dni wstecz obejmuje. Zwraca surowy JSON. */
    public JsonNode meaList(int shopId, String token, int day) {
        return post("mea/", Map.of(
                "appKEY", appKey,
                "shopID", Integer.toString(shopId),
                "token", token,
                "day", Integer.toString(day)));
    }

    /**
     * Plik MEA z portalu ({@code /mea/download/}); {@code file} to ścieżka z listy MEA (np.
     * {@code ../../data/mea/xls/815/81534.xls}), wysyłana w Base64 jak w eMEA. Pusta odpowiedź = pliku jeszcze nie ma
     * (portal wgrywa gazetki).
     */
    public byte[] meaFile(int shopId, String token, String file) {
        byte[] body = send("mea/download/", Map.of(
                "appKEY", appKey,
                "shopID", Integer.toString(shopId),
                "token", token,
                "file", Base64.getEncoder().encodeToString(file.getBytes(StandardCharsets.US_ASCII))));
        if (body.length == 0) {
            throw new Unavailable("Portal nie oddał pliku MEA. Trwa pewnie wgrywanie gazetek, spróbuj za chwilę");
        }
        return body;
    }

    private JsonNode post(String path, Map<String, String> fields) {
        byte[] body = send(path, fields);
        try {
            return json.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new Unavailable("Nieczytelna odpowiedź z portalu Grupy Muszkieterów");
        }
    }

    private byte[] send(String path, Map<String, String> fields) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form(fields), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            log.warn("Portal {}: {}", path, exception.toString());
            throw new Unavailable("Brak połączenia z portalem Grupy Muszkieterów. Spróbuj ponownie za chwilę");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Przerwano zapytanie do portalu Grupy Muszkieterów");
        }
        if (response.statusCode() != 200) {
            log.warn("Portal {}: HTTP {}", path, response.statusCode());
            throw new Unavailable("Portal Grupy Muszkieterów odpowiedział błędem (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    private static Instant parseStop(String stop) {
        try {
            return LocalDateTime.parse(stop, STOP_FORMAT).atZone(StoreTime.ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            // Bez czytelnej daty wygaśnięcia zakładamy krótką ważność, żeby token odświeżył się przy kolejnym użyciu.
            return Instant.now().plus(Duration.ofMinutes(5));
        }
    }

    private static String form(Map<String, String> fields) {
        Map<String, String> ordered = new LinkedHashMap<>(fields);
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Brak algorytmu MD5", exception);
        }
    }
}
