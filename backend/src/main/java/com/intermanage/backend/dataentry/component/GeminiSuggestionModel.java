package com.intermanage.backend.dataentry.component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini przez REST {@code generateContent} ({@code app.ai.gemini.*}, klucz {@code GEMINI_API_KEY}): odpowiedź jako
 * JSON według schematu. Przeciążenie i limit (429, 500, 503) ponawia z odstępem z odpowiedzi, łącznie najwyżej
 * {@code retry-budget} czekania; potem {@link SuggestionModel.Unavailable}.
 */
@Component
public class GeminiSuggestionModel implements SuggestionModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiSuggestionModel.class);
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"|retry in ([0-9.]+)s");
    private static final Duration FIRST_WAIT = Duration.ofSeconds(2);

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final String model;
    private final String thinkingLevel;
    private final Duration retryBudget;

    public GeminiSuggestionModel(JsonMapper json,
            @Value("${app.ai.gemini.base-url}") String baseUrl,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.model}") String model,
            @Value("${app.ai.gemini.thinking-level}") String thinkingLevel,
            @Value("${app.ai.gemini.read-timeout}") Duration readTimeout,
            @Value("${app.ai.gemini.retry-budget}") Duration retryBudget) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.thinkingLevel = thinkingLevel;
        this.retryBudget = retryBudget;
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty();
    }

    @Override
    public List<Answer> suggest(String systemInstruction, String prompt) {
        if (!available()) {
            throw new Unavailable("Brak klucza Gemini (GEMINI_API_KEY)");
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", SCHEMA,
                        "thinkingConfig", Map.of("thinkingLevel", thinkingLevel)));
        Instant deadline = Instant.now().plus(retryBudget);
        Duration wait = FIRST_WAIT;
        while (true) {
            try {
                JsonNode response = rest.post().uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json.writeValueAsString(body))
                        .retrieve().body(JsonNode.class);
                return answers(response);
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                boolean temporary = status.value() == 429 || status.is5xxServerError();
                String detail = e.getResponseBodyAsString();
                if (!temporary) {
                    log.warn("Gemini odrzucił zapytanie: HTTP {} {}", status.value(), abbreviate(detail));
                    throw new Unavailable("Model odrzucił zapytanie (HTTP " + status.value() + ")", e);
                }
                Duration delay = retryDelay(detail).orElse(wait);
                if (Instant.now().plus(delay).isAfter(deadline)) {
                    throw new Unavailable(status.value() == 429 ? "Model chwilowo przekroczył limit zapytań"
                            : "Model jest chwilowo przeciążony", e);
                }
                log.info("Gemini HTTP {}, ponawiam za {} s", status.value(), delay.toSeconds());
                sleep(delay);
                wait = wait.multipliedBy(2);
            } catch (ResourceAccessException e) {
                throw new Unavailable("Model nie odpowiedział", e);
            }
        }
    }

    private List<Answer> answers(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                text.append(part.path("text").asString());
            }
        }
        if (text.isEmpty()) {
            throw new Unavailable("Model nie zwrócił odpowiedzi");
        }
        JsonNode items;
        try {
            items = json.readTree(text.toString()).path("items");
        } catch (RuntimeException e) {
            throw new Unavailable("Model zwrócił nieczytelną odpowiedź", e);
        }
        List<Answer> answers = new ArrayList<>();
        for (JsonNode item : items) {
            List<String> alternatives = new ArrayList<>();
            item.path("alternatives").forEach(code -> alternatives.add(code.asString()));
            answers.add(new Answer(item.path("id").asInt(-1), text(item, "name"), text(item, "brand"),
                    text(item, "subfamily"), alternatives,
                    item.path("confidence").isNumber() ? item.path("confidence").asDouble() : null,
                    text(item, "unit"),
                    item.path("net_content").isNumber() ? item.path("net_content").decimalValue() : null));
        }
        return answers;
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }

    private static java.util.Optional<Duration> retryDelay(String body) {
        Matcher matcher = RETRY_DELAY.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        String seconds = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        long millis = new BigDecimal(seconds).movePointRight(3).longValue();
        return java.util.Optional.of(Duration.ofMillis(millis).plusSeconds(1));
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Przerwano oczekiwanie na model", e);
        }
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("items", Map.of(
                    "type", "ARRAY",
                    "items", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "id", Map.of("type", "INTEGER"),
                                    "name", Map.of("type", "STRING"),
                                    "brand", Map.of("type", "STRING"),
                                    "subfamily", Map.of("type", "STRING"),
                                    "alternatives", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                                    "confidence", Map.of("type", "NUMBER"),
                                    "unit", Map.of("type", "STRING", "enum", List.of("kg", "L", "szt.")),
                                    "net_content", Map.of("type", "NUMBER")),
                            "required", List.of("id", "name", "brand", "subfamily", "alternatives", "confidence",
                                    "unit", "net_content")))),
            "required", List.of("items"));
}
