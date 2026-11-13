package com.intermanage.backend.ksef.component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.intermanage.backend.ksef.dto.KsefInvoiceMetadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Klient API KSeF 2.0 ({@code app.ksef.base-url}, domyślnie produkcja): logowanie certyfikatem KSeF (challenge,
 * podpisany XAdES {@code AuthTokenRequest}, oczekiwanie na weryfikację, odebranie tokenu dostępu), metadane faktur
 * zakupowych i XML faktury. Błędy KSeF zamienia na {@link KsefException} z opisem z odpowiedzi.
 */
@Component
public class KsefClient {

    private static final Logger log = LoggerFactory.getLogger(KsefClient.class);
    /** Największa strona metadanych w KSeF; zapytań jest mało (16 na minutę, 20 na godzinę), więc bierzemy pełne. */
    public static final int PAGE_SIZE = 250;
    private static final Duration AUTH_WAIT = Duration.ofSeconds(60);
    private static final Duration AUTH_POLL = Duration.ofSeconds(1);
    private static final int AUTH_IN_PROGRESS = 100;
    private static final int AUTH_SUCCESS = 200;

    private final RestClient rest;
    private final KsefXadesSigner signer;
    private final JsonMapper json;

    public KsefClient(KsefXadesSigner signer, JsonMapper json, @Value("${app.ksef.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        // Strona 250 faktur z dużego okna potrafi się liczyć w KSeF ponad minutę.
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader("X-Error-Format", "problem-details")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw error(response);
                })
                .build();
        this.signer = signer;
        this.json = json;
    }

    public record AccessToken(String token, Instant validUntil) {
    }

    /**
     * Wyjątek z KSeF; {@code retryAfter} ustawione przy przekroczeniu limitu zapytań (HTTP 429), {@code temporary}
     * przy braku odpowiedzi (timeout, zerwane połączenie) albo błędzie serwera KSeF (5xx) - takie zapytanie warto
     * powtórzyć.
     */
    public static class KsefException extends RuntimeException {

        private final Duration retryAfter;
        private final boolean temporary;

        public KsefException(String message, Duration retryAfter) {
            this(message, retryAfter, false);
        }

        public KsefException(String message, Duration retryAfter, boolean temporary) {
            super(message);
            this.retryAfter = retryAfter;
            this.temporary = temporary;
        }

        public boolean temporary() {
            return temporary;
        }

        public Duration retryAfter() {
            return retryAfter;
        }

        public boolean rateLimited() {
            return retryAfter != null;
        }
    }

    /** Logowanie w kontekście NIP firmy certyfikatem osoby z uprawnieniem do faktur. */
    public AccessToken authenticate(String nip, X509Certificate certificate, PrivateKey key) {
        return call(() -> {
            JsonNode challenge = rest.post().uri("/auth/challenge").retrieve().body(JsonNode.class);
            String signed = signer.signedAuthRequest(challenge.path("challenge").asString(), nip, certificate, key);
            JsonNode init = rest.post().uri("/auth/xades-signature")
                    .contentType(MediaType.APPLICATION_XML).body(signed)
                    .retrieve().body(JsonNode.class);
            String referenceNumber = init.path("referenceNumber").asString();
            String authenticationToken = init.path("authenticationToken").path("token").asString();

            Instant deadline = Instant.now().plus(AUTH_WAIT);
            while (true) {
                JsonNode status = rest.get().uri("/auth/{referenceNumber}", referenceNumber)
                        .headers(headers -> headers.setBearerAuth(authenticationToken))
                        .retrieve().body(JsonNode.class).path("status");
                int code = status.path("code").asInt();
                if (code == AUTH_SUCCESS) {
                    break;
                }
                if (code != AUTH_IN_PROGRESS) {
                    throw new KsefException(describe(status), null);
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new KsefException("KSeF nie zakończył weryfikacji certyfikatu w ciągu minuty - spróbuj "
                            + "ponownie za chwilę", null);
                }
                sleep(AUTH_POLL);
            }

            JsonNode access = rest.post().uri("/auth/token/redeem")
                    .headers(headers -> headers.setBearerAuth(authenticationToken))
                    .retrieve().body(JsonNode.class).path("accessToken");
            return new AccessToken(access.path("token").asString(),
                    OffsetDateTime.parse(access.path("validUntil").asString()).toInstant());
        });
    }

    /**
     * Strona metadanych faktur, w których firma jest nabywcą (Podmiot 2), po dacie trwałego zapisu rosnąco - tak KSeF
     * zaleca pobieranie przyrostowe. Zakres nie dłuższy niż 100 dni, {@code to} przycięte do znacznika, poniżej
     * którego dane są kompletne ({@code permanentStorageHwmDate}).
     */
    public KsefInvoiceMetadata.Page purchaseInvoices(String accessToken, Instant from, Instant to, int pageOffset) {
        Map<String, Object> filters = Map.of(
                "subjectType", "Subject2",
                "dateRange", Map.of(
                        "dateType", "PermanentStorage",
                        "from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC).toString(),
                        "to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC).toString(),
                        "restrictToPermanentStorageHwmDate", true));
        return call(() -> rest.post()
                .uri(uri -> uri.path("/invoices/query/metadata")
                        .queryParam("sortOrder", "Asc")
                        .queryParam("pageOffset", pageOffset)
                        .queryParam("pageSize", PAGE_SIZE)
                        .build())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON).body(filters)
                .retrieve().body(KsefInvoiceMetadata.Page.class));
    }

    /** Faktura w XML (schemat FA) po numerze KSeF. */
    public byte[] invoiceXml(String accessToken, String ksefNumber) {
        return call(() -> rest.get().uri("/invoices/ksef/{ksefNumber}", ksefNumber)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .accept(MediaType.APPLICATION_XML)
                .retrieve().body(byte[].class));
    }

    /** Certyfikat klucza publicznego MF do szyfrowania klucza symetrycznego (DER w Base64) i jego identyfikator. */
    public record PublicKey(String certificate, String publicKeyId) {
    }

    /** Obowiązujący dziś klucz publiczny MF do szyfrowania klucza paczki (bez logowania). */
    public PublicKey symmetricKeyEncryptionKey() {
        JsonNode certificates = call(() -> rest.get().uri("/security/public-key-certificates")
                .retrieve().body(JsonNode.class));
        Instant now = Instant.now();
        for (JsonNode certificate : certificates) {
            boolean forKeys = false;
            for (JsonNode usage : certificate.path("usage")) {
                forKeys |= "SymmetricKeyEncryption".equals(usage.asString(""));
            }
            Instant validFrom = OffsetDateTime.parse(certificate.path("validFrom").asString()).toInstant();
            Instant validTo = OffsetDateTime.parse(certificate.path("validTo").asString()).toInstant();
            if (forKeys && !now.isBefore(validFrom) && now.isBefore(validTo)) {
                return new PublicKey(certificate.path("certificate").asString(),
                        certificate.path("publicKeyId").asString(null));
            }
        }
        throw new KsefException("KSeF nie podał klucza publicznego do szyfrowania paczki faktur", null);
    }

    /** Część paczki: kolejność, adres do pobrania (bez tokenu i limitów) i skrót SHA-256 zaszyfrowanej części. */
    public record ExportPart(int ordinalNumber, String method, String url, String encryptedPartHash) {
    }

    /**
     * Stan eksportu: {@code code} 100 w toku, 200 gotowe, inne to błąd ({@code description}). Przy gotowej paczce
     * części i dane do kolejnego eksportu: {@code truncated} (paczka ucięta na 10 tys. faktur albo 1 GB, kolejny
     * eksport od {@code lastPermanentStorageDate}) i {@code permanentStorageHwmDate} (poniżej dane są kompletne).
     */
    public record ExportStatus(int code, String description, List<ExportPart> parts, int invoiceCount,
            boolean truncated, Instant lastPermanentStorageDate, Instant permanentStorageHwmDate) {

        public boolean inProgress() {
            return code == 100;
        }

        public boolean ready() {
            return code == 200;
        }
    }

    /**
     * Zlecenie eksportu XML faktur zakupowych (Podmiot 2) trwale zapisanych w zakresie, rosnąco; zakres do 100 dni,
     * {@code to} przycięte do znacznika kompletności. Klucz AES paczki przychodzi zaszyfrowany kluczem publicznym MF.
     */
    public String startPurchaseExport(String accessToken, Instant from, Instant to, String encryptedKey,
            String initializationVector, String publicKeyId) {
        Map<String, Object> encryption = new java.util.LinkedHashMap<>();
        encryption.put("encryptedSymmetricKey", encryptedKey);
        encryption.put("initializationVector", initializationVector);
        if (publicKeyId != null) {
            encryption.put("publicKeyId", publicKeyId);
        }
        Map<String, Object> body = Map.of(
                "encryption", encryption,
                "filters", Map.of(
                        "subjectType", "Subject2",
                        "dateRange", Map.of(
                                "dateType", "PermanentStorage",
                                "from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC).toString(),
                                "to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC).toString(),
                                "restrictToPermanentStorageHwmDate", true)));
        return call(() -> rest.post().uri("/invoices/exports")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class).path("referenceNumber").asString());
    }

    public ExportStatus exportStatus(String accessToken, String referenceNumber) {
        JsonNode root = call(() -> rest.get().uri("/invoices/exports/{referenceNumber}", referenceNumber)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));
        JsonNode status = root.path("status");
        JsonNode pack = root.path("package");
        List<ExportPart> parts = new ArrayList<>();
        for (JsonNode part : pack.path("parts")) {
            parts.add(new ExportPart(part.path("ordinalNumber").asInt(), part.path("method").asString("GET"),
                    part.path("url").asString(), part.path("encryptedPartHash").asString(null)));
        }
        parts.sort(java.util.Comparator.comparingInt(ExportPart::ordinalNumber));
        return new ExportStatus(status.path("code").asInt(), describe(status), parts,
                pack.path("invoiceCount").asInt(0), pack.path("isTruncated").asBoolean(false),
                instant(pack.path("lastPermanentStorageDate")), instant(pack.path("permanentStorageHwmDate")));
    }

    /** Część paczki z adresu ze statusu: bez tokenu (adres jest podpisany) i bez limitów API. */
    public byte[] exportPart(ExportPart part) {
        return call(() -> RestClient.create().method(org.springframework.http.HttpMethod.valueOf(part.method()))
                .uri(java.net.URI.create(part.url()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new KsefException("Nie udało się pobrać części paczki faktur (HTTP "
                            + response.getStatusCode().value() + ")", null, response.getStatusCode().is5xxServerError());
                })
                .body(byte[].class));
    }

    private static Instant instant(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : OffsetDateTime.parse(value.asString()).toInstant();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new KsefException("Brak odpowiedzi z KSeF (" + e.getMostSpecificCause().getMessage() + ")", null, true);
        }
    }

    /** Opis błędu z odpowiedzi KSeF: problem+json ({@code title}/{@code detail}) albo {@code exception} z listą. */
    private KsefException error(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        log.warn("KSeF odpowiedział {}: {}", status, body.length() > 2000 ? body.substring(0, 2000) : body);
        Duration retryAfter = status == 429 ? retryAfter(response.getHeaders()) : null;
        String message = messageFrom(body);
        if (message == null) {
            message = switch (status) {
                case 401 -> "KSeF odrzucił logowanie (401)";
                case 403 -> "Brak uprawnień w KSeF - osoba z certyfikatu musi mieć prawo do przeglądania faktur "
                        + "w kontekście tego NIP";
                case 429 -> "Przekroczony limit zapytań do KSeF";
                default -> "KSeF zwrócił błąd " + status;
            };
        }
        return new KsefException(message, retryAfter, status >= 500);
    }

    private String messageFrom(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(body);
            List<String> parts = new ArrayList<>();
            for (JsonNode detail : root.path("exception").path("exceptionDetailList")) {
                parts.add(detail.path("exceptionDescription").asString(""));
                detail.path("details").forEach(item -> parts.add(item.asString("")));
            }
            if (parts.isEmpty()) {
                if (root.hasNonNull("detail")) {
                    parts.add(root.path("detail").asString(""));
                } else if (root.hasNonNull("title")) {
                    parts.add(root.path("title").asString(""));
                }
                root.path("status").path("details").forEach(item -> parts.add(item.asString("")));
                root.path("errors").forEach(item -> parts.add(item.isString() ? item.asString()
                        : item.path("description").asString(item.toString())));
            }
            String message = String.join(" ", parts.stream().filter(part -> !part.isBlank()).toList()).trim();
            return message.isEmpty() ? null : message;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return Duration.ofSeconds(value != null ? Long.parseLong(value.trim()) : 60);
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(60);
        }
    }

    private static String describe(JsonNode status) {
        List<String> parts = new ArrayList<>();
        parts.add(status.path("description").asString("Uwierzytelnianie zakończone niepowodzeniem"));
        status.path("details").forEach(detail -> parts.add(detail.asString("")));
        return String.join(" - ", parts.stream().filter(part -> !part.isBlank()).toList());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KsefException("Przerwano logowanie do KSeF", null);
        }
    }
}
