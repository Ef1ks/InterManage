package com.intermanage.backend.push.component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wysyłka przez usługę push Expo ({@code https://exp.host/--/api/v2/push/send}, paczki po 100 wiadomości). Expo
 * doręcza na iOS przez APNs, a na Androida przez FCM: Android wymaga projektu Firebase z kluczem w EAS, bez tego
 * wiadomość wraca z błędem. Bez {@code app.push.enabled} nic nie wychodzi, a wiadomości trafiają do logów
 * ({@code [PUSH] …}), jak maile bez serwera pocztowego. Zwraca tokeny, których urządzenie już nie ma
 * ({@code DeviceNotRegistered}), żeby je wyłączyć.
 */
@Component
public class ExpoPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);
    private static final int CHUNK = 100;

    public record Message(String token, String title, String body, Map<String, String> data) {
    }

    private final JsonMapper json;
    private final boolean enabled;
    private final URI url;
    private final String accessToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ExpoPushClient(JsonMapper json, @Value("${app.push.enabled}") boolean enabled,
            @Value("${app.push.expo-url}") String url, @Value("${app.push.access-token}") String accessToken) {
        this.json = json;
        this.enabled = enabled;
        this.url = URI.create(url);
        this.accessToken = accessToken == null || accessToken.isBlank() ? null : accessToken.strip();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Wysyła wiadomości; zwraca tokeny odrzucone jako nieistniejące. Błąd usługi idzie do logów, nie dalej. */
    public List<String> send(List<Message> messages) {
        List<String> gone = new ArrayList<>();
        if (!enabled) {
            messages.forEach(message -> log.info("[PUSH] {}… · {} · {}", abbreviate(message.token()), message.title(),
                    message.body()));
            return gone;
        }
        for (int from = 0; from < messages.size(); from += CHUNK) {
            List<Message> chunk = messages.subList(from, Math.min(messages.size(), from + CHUNK));
            try {
                gone.addAll(sendChunk(chunk));
            } catch (Exception e) {
                log.warn("Wysyłka powiadomień push nie powiodła się: {}", e.getMessage());
            }
        }
        return gone;
    }

    private List<String> sendChunk(List<Message> chunk) throws Exception {
        List<Map<String, Object>> body = chunk.stream().map(message -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("to", message.token());
            item.put("title", message.title());
            item.put("body", message.body());
            item.put("data", message.data());
            item.put("sound", "default");
            item.put("channelId", "default");
            return item;
        }).toList();
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = http.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        List<String> gone = new ArrayList<>();
        JsonNode tickets = json.readTree(response.body()).path("data");
        for (int i = 0; i < tickets.size() && i < chunk.size(); i++) {
            JsonNode ticket = tickets.get(i);
            if ("error".equals(ticket.path("status").asString())) {
                String error = ticket.path("details").path("error").asString();
                if ("DeviceNotRegistered".equals(error)) {
                    gone.add(chunk.get(i).token());
                } else {
                    log.warn("Powiadomienie push odrzucone: {} {}", error, ticket.path("message").asString());
                }
            }
        }
        return gone;
    }

    private static String abbreviate(String token) {
        return token.length() <= 24 ? token : token.substring(0, 24);
    }
}
