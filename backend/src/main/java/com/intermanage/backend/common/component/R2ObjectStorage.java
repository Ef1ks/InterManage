package com.intermanage.backend.common.component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cloudflare R2 przez API zgodne z S3: zapis pliku żądaniem PUT podpisanym AWS Signature V4 (region {@code auto}),
 * bez AWS SDK. {@code R2_S3_API} to adres z nazwą bucketu ({@code https://<konto>.r2.cloudflarestorage.com/<bucket>}),
 * {@code R2_PUBLIC_URL} domena, pod którą bucket jest publiczny (np. {@code cdn.intermanage.pl}).
 */
@Component
public class R2ObjectStorage implements ObjectStorage {

    private static final String REGION = "auto";
    private static final String SERVICE = "s3";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Plik pod danym kluczem się nie zmienia, więc przeglądarka i CDN mogą go trzymać rok. */
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9][a-z0-9/._-]*");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String publicUrl;

    public R2ObjectStorage(@Value("${app.storage.r2.endpoint:}") String endpoint,
            @Value("${app.storage.r2.access-key-id:}") String accessKeyId,
            @Value("${app.storage.r2.secret-access-key:}") String secretAccessKey,
            @Value("${app.storage.r2.public-url:}") String publicUrl) {
        this.endpoint = withoutTrailingSlash(endpoint);
        this.accessKeyId = accessKeyId == null ? "" : accessKeyId.strip();
        this.secretAccessKey = secretAccessKey == null ? "" : secretAccessKey.strip();
        String url = withoutTrailingSlash(publicUrl);
        this.publicUrl = url.isEmpty() || url.contains("://") ? url : "https://" + url;
    }

    @Override
    public boolean enabled() {
        return !endpoint.isEmpty() && !accessKeyId.isEmpty() && !secretAccessKey.isEmpty() && !publicUrl.isEmpty();
    }

    @Override
    public void put(String key, String contentType, byte[] content) {
        if (!enabled()) {
            throw new IllegalStateException("R2 nie jest skonfigurowane");
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            // Klucze składa aplikacja; same bezpieczne znaki, więc ścieżki nie trzeba kodować przy podpisie.
            throw new IllegalArgumentException("Niedozwolony klucz pliku: " + key);
        }
        URI uri = URI.create(endpoint + "/" + key);
        String payloadHash = hex(sha256(content));
        String amzDate = AMZ_DATE.format(Instant.now());
        Map<String, String> headers = new TreeMap<>();
        headers.put("cache-control", CACHE_CONTROL);
        headers.put("content-type", contentType);
        headers.put("host", uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .header("Authorization", authorization("PUT", uri.getRawPath(), "", headers, payloadHash,
                        accessKeyId, secretAccessKey, REGION, amzDate));
        // Host ustawia sam HttpClient (z adresu), więc nie wolno go podać drugi raz.
        headers.forEach((name, value) -> {
            if (!name.equals("host")) {
                request.header(name, value);
            }
        });
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ObjectStorageException("R2 odrzuciło zapis pliku (" + response.statusCode() + "): "
                        + response.body().strip(), null);
            }
        } catch (IOException e) {
            throw new ObjectStorageException("Brak połączenia z R2", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObjectStorageException("Przerwano zapis pliku w R2", e);
        }
    }

    @Override
    public String publicUrl(String key) {
        return publicUrl + "/" + key;
    }

    /**
     * Nagłówek {@code Authorization} AWS Signature V4. {@code headers}: nazwy małymi literami, wszystkie podpisywane.
     */
    public static String authorization(String method, String path, String query, Map<String, String> headers,
            String payloadHash, String accessKeyId, String secretAccessKey, String region, String amzDate) {
        Map<String, String> sorted = new TreeMap<>(headers);
        StringBuilder canonicalHeaders = new StringBuilder();
        sorted.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value.strip()).append('\n'));
        String signedHeaders = String.join(";", sorted.keySet());
        String canonicalRequest = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;

        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, SERVICE);
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String withoutTrailingSlash(String value) {
        String result = value == null ? "" : value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
