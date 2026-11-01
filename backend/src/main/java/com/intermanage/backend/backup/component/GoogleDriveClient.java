package com.intermanage.backend.backup.component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dysk Google przez REST (OAuth 2.0 i Drive API v3), na kliencie HTTP z JDK, bo plik kopii idzie strumieniem z dysku.
 * Zakres {@code drive.file}: aplikacja widzi tylko pliki i foldery, które sama utworzyła, nie resztę Dysku.
 */
@Component
public class GoogleDriveClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);

    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    private static final String DRIVE_URL = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    static final String FOLDER_NAME = "Intermanage - kopie zapasowe";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofHours(3);

    /** Refresh token wygasł albo został cofnięty w koncie Google: trzeba połączyć konto ponownie. */
    public static class RevokedException extends BackupException {
        public RevokedException() {
            super("Google odrzucił połączenie z kontem (cofnięty dostęp albo zmienione hasło) - połącz konto "
                    + "Google ponownie w Ustawieniach → Kopia zapasowa");
        }
    }

    public record Tokens(String accessToken, String refreshToken) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final JsonMapper json;
    private final String clientId;
    private final String clientSecret;

    public GoogleDriveClient(JsonMapper json, @Value("${app.backup.google.client-id:}") String clientId,
            @Value("${app.backup.google.client-secret:}") String clientSecret) {
        this.json = json;
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
    }

    /** Czy serwer ma dane klienta OAuth (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET). */
    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** Adres zgody Google. {@code prompt=consent} wymusza refresh token także przy ponownym łączeniu. */
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", SCOPE);
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("include_granted_scopes", "false");
        params.put("state", state);
        return AUTHORIZE_URL + "?" + form(params);
    }

    public Tokens exchangeCode(String code, String redirectUri) {
        JsonNode body = tokenRequest(Map.of("code", code, "client_id", clientId, "client_secret", clientSecret,
                "redirect_uri", redirectUri, "grant_type", "authorization_code"));
        String refresh = body.path("refresh_token").asString(null);
        if (refresh == null) {
            throw new BackupException("Google nie zwrócił tokenu odświeżania - spróbuj połączyć konto ponownie");
        }
        return new Tokens(body.path("access_token").asString(), refresh);
    }

    public String accessToken(String refreshToken) {
        return tokenRequest(Map.of("refresh_token", refreshToken, "client_id", clientId,
                "client_secret", clientSecret, "grant_type", "refresh_token")).path("access_token").asString();
    }

    /** Adres e-mail konta, z którym jest połączenie. */
    public String accountEmail(String accessToken) {
        JsonNode body = send(authorized(accessToken, DRIVE_URL + "/about?fields=user(emailAddress)").GET().build(),
                "odczyt konta Google");
        return body.path("user").path("emailAddress").asString("konto Google");
    }

    /** Folder kopii: zapisany, o ile istnieje i nie jest w koszu, inaczej nowy. Zwraca jego id. */
    public String ensureFolder(String accessToken, String folderId) {
        if (folderId != null) {
            HttpResponse<String> response = call(authorized(accessToken,
                    DRIVE_URL + "/files/" + folderId + "?fields=id,trashed").GET().build());
            if (response.statusCode() == 200 && !read(response.body()).path("trashed").asBoolean(false)) {
                return folderId;
            }
            if (response.statusCode() != 404 && response.statusCode() != 200) {
                throw error("sprawdzenie folderu kopii", response);
            }
        }
        String metadata = json.writeValueAsString(Map.of("name", FOLDER_NAME, "mimeType", FOLDER_MIME));
        JsonNode created = send(authorized(accessToken, DRIVE_URL + "/files?fields=id")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(BodyPublishers.ofString(metadata)).build(), "utworzenie folderu kopii");
        return created.path("id").asString();
    }

    /** Wysyła plik do folderu (upload wznawialny: metadane, potem treść strumieniem). Zwraca id pliku. */
    public String upload(String accessToken, String folderId, String name, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new BackupException("Nie da się odczytać pliku kopii", e);
        }
        String metadata = json.writeValueAsString(Map.of("name", name, "parents", List.of(folderId)));
        HttpResponse<String> session = call(authorized(accessToken, UPLOAD_URL + "?uploadType=resumable")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", String.valueOf(size))
                .POST(BodyPublishers.ofString(metadata)).build());
        String location = session.headers().firstValue("Location").orElse(null);
        if (session.statusCode() != 200 || location == null) {
            throw error("rozpoczęcie wysyłania kopii", session);
        }
        BodyPublisher content;
        try {
            content = BodyPublishers.ofFile(file);
        } catch (IOException e) {
            throw new BackupException("Nie da się odczytać pliku kopii", e);
        }
        HttpResponse<String> uploaded = call(HttpRequest.newBuilder(URI.create(location)).timeout(UPLOAD_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/octet-stream")
                .PUT(content).build());
        if (uploaded.statusCode() != 200 && uploaded.statusCode() != 201) {
            throw error("wysłanie kopii", uploaded);
        }
        return read(uploaded.body()).path("id").asString();
    }

    /** Przenosi plik do kosza Dysku (Google opróżnia kosz po 30 dniach). Plik, którego już nie ma, pomija. */
    public void trash(String accessToken, String fileId) {
        HttpResponse<String> response = call(authorized(accessToken, DRIVE_URL + "/files/" + fileId + "?fields=id")
                .header("Content-Type", "application/json; charset=UTF-8")
                .method("PATCH", BodyPublishers.ofString("{\"trashed\":true}")).build());
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw error("przeniesienie starej kopii do kosza", response);
        }
    }

    /** Cofa dostęp aplikacji w koncie Google. Błąd tylko w logach: połączenie i tak jest odłączane. */
    public void revoke(String refreshToken) {
        try {
            HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(REVOKE_URL)).timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(form(Map.of("token", refreshToken)))).build());
            if (response.statusCode() != 200) {
                log.info("Google nie cofnął tokenu: HTTP {}", response.statusCode());
            }
        } catch (BackupException e) {
            log.info("Google nie cofnął tokenu: {}", e.getMessage());
        }
    }

    private JsonNode tokenRequest(Map<String, String> params) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(TOKEN_URL)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form(params))).build());
        if (response.statusCode() == 400 && "invalid_grant".equals(read(response.body()).path("error").asString(""))
                && params.containsKey("refresh_token")) {
            throw new RevokedException();
        }
        if (response.statusCode() != 200) {
            throw error("logowanie do Google", response);
        }
        return read(response.body());
    }

    private static HttpRequest.Builder authorized(String accessToken, String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken);
    }

    private JsonNode send(HttpRequest request, String action) {
        HttpResponse<String> response = call(request);
        if (response.statusCode() / 100 != 2) {
            throw error(action, response);
        }
        return read(response.body());
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BackupException("Dysk Google nie odpowiedział: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("Połączenie z Dyskiem Google zostało przerwane", e);
        }
    }

    private BackupException error(String action, HttpResponse<String> response) {
        JsonNode body = read(response.body());
        JsonNode error = body.path("error");
        String message = error.isObject() ? error.path("message").asString("")
                : body.path("error_description").asString(error.asString(""));
        log.warn("Google: {} - HTTP {} {}", action, response.statusCode(), response.body());
        return new BackupException("Google: " + action + " nie powiodło się (HTTP " + response.statusCode()
                + (message.isBlank() ? "" : ": " + message) + ")");
    }

    private JsonNode read(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
