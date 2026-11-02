package com.intermanage.backend.meat.component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Odczyt WZ przez Gemini ({@code generateContent} z plikiem w treści zapytania, {@code app.ai.gemini.scan-model},
 * domyślnie gemini-3.5-flash: lite brał znaczki przy ilościach za poprawki odręczne). Przeciążenie i limit (429, 5xx)
 * ponawia jak {@code GeminiSuggestionModel}, łącznie najwyżej {@code retry-budget} czekania.
 */
@Component
public class GeminiMeatScanReader implements MeatScanReader {

    private static final Logger log = LoggerFactory.getLogger(GeminiMeatScanReader.class);
    private static final Pattern RETRY_DELAY = Pattern.compile("\"retryDelay\"\\s*:\\s*\"([0-9.]+)s\"");
    private static final Duration FIRST_WAIT = Duration.ofSeconds(2);

    static final String INSTRUCTION = """
            Odczytujesz zeskanowany albo sfotografowany dokument dostawy mięsa (WZ, potwierdzenie sprzedaży, \
            handlowy dokument identyfikacyjny) dla sklepu Intermarché.

            Dostawca:
            - Dostawca to zakład, który wydał towar: nagłówek, logo, pieczątka, stopka, numer weterynaryjny.
            - SCA PR POLSKA (NIP 7821977018) jest nabywcą albo płatnikiem, a TRYTOMA (NIP 7812022538) odbiorcą: \
            to nigdy nie jest dostawca.
            - Nagłówek bywa ucięty, a pieczątka zasłania cyfry. Przepisz tylko to, co naprawdę widać; czego nie \
            widać, zostaw puste. NIP dostawcy przepisz cyframi, gdy jest cały.
            - Napisy programów i drukarni ("Eksport z MOM", "Druk Streamsoft PRESTIŻ", "Wystawił", podpisy osób) \
            to nie nazwa dostawcy. Gdy nazwy firmy nie widać, supplierName zostaw puste.
            - supplierNo: numer dostawcy z listy dostawców sklepu, gdy jesteś pewien (nazwa, NIP, także fragment \
            NIP-u z nagłówka); inaczej 0.

            Pozycje:
            - Tylko towar. Pomiń opakowania (palety, pojemniki E2, styroboksy, rozliczenie i saldo opakowań, bon \
            zwrotny) i wiersze sum.
            - name: nazwa jak na dokumencie. code: kod towaru albo EAN z dokumentu, gdy jest (bez PKWiU, bez "0").
            - unitNetPrice: cena netto za kg (za jednostkę) z dokumentu.
            - Odręczna poprawka ceny ma pierwszeństwo przed drukiem; wtedy handwrittenCorrection = true i krótko \
            opisz ją w note. Odręczne znaczki (ptaszki, kreski) przy pozycjach to nie poprawki. Odręczną poprawkę \
            samej ilości też zaznacz, choć ceny nie zmienia.
            - articleNo: numer artykułu z listy mięsa z kartoteki, który jest tym samym towarem (gatunek z marki: \
            KURCZAK, INDYK, WOLOWINA, WIEPRZOWE; "luz", "promo", "kl. A" nie zmieniają towaru). Gdy żaden nie \
            pasuje albo nie jesteś pewien, 0.

            Daty w formacie RRRR-MM-DD: issueDate to data wystawienia, shipDate data wysyłki albo sprzedaży, gdy \
            jest na dokumencie (inaczej puste).""";

    private final RestClient rest;
    private final JsonMapper json;
    private final String apiKey;
    private final String model;
    private final Duration retryBudget;

    public GeminiMeatScanReader(JsonMapper json,
            @Value("${app.ai.gemini.base-url}") String baseUrl,
            @Value("${app.ai.gemini.api-key:}") String apiKey,
            @Value("${app.ai.gemini.scan-model}") String model,
            @Value("${app.ai.gemini.scan-read-timeout}") Duration readTimeout,
            @Value("${app.ai.gemini.retry-budget}") Duration retryBudget) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.retryBudget = retryBudget;
    }

    @Override
    public Reading read(byte[] content, String contentType, List<String> articles, List<String> suppliers) {
        if (apiKey.isEmpty()) {
            throw new Unavailable("Odczyt skanów nie jest skonfigurowany (brak klucza Gemini)");
        }
        String lists = "Mięso z kartoteki sklepu (numer | nazwa | marka):\n" + numbered(articles)
                + "\nDostawcy sklepu (numer | nazwa | NIP):\n" + numbered(suppliers);
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", INSTRUCTION))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("text", lists),
                        Map.of("inline_data", Map.of("mime_type", contentType,
                                "data", Base64.getEncoder().encodeToString(content))),
                        Map.of("text", "Odczytaj ten dokument.")))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", SCHEMA,
                        "thinkingConfig", Map.of("thinkingLevel", "low")));
        return reading(generate(body));
    }

    private static String numbered(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            text.append(i + 1).append(" | ").append(items.get(i)).append('\n');
        }
        return text.toString();
    }

    private JsonNode generate(Map<String, Object> body) {
        Instant deadline = Instant.now().plus(retryBudget);
        Duration wait = FIRST_WAIT;
        String payload = json.writeValueAsString(body);
        while (true) {
            try {
                JsonNode response = rest.post().uri("/models/{model}:generateContent", model)
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve().body(JsonNode.class);
                return answer(response);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && !e.getStatusCode().is5xxServerError()) {
                    log.warn("Gemini odrzucił skan: HTTP {} {}", status, abbreviate(e.getResponseBodyAsString()));
                    throw new Unavailable("Model odrzucił plik (HTTP " + status + ")", e);
                }
                Duration delay = retryDelay(e.getResponseBodyAsString(), wait);
                if (Instant.now().plus(delay).isAfter(deadline)) {
                    throw new Unavailable(status == 429 ? "Model chwilowo przekroczył limit zapytań"
                            : "Model jest chwilowo przeciążony", e);
                }
                log.info("Gemini HTTP {} przy skanie, ponawiam za {} s", status, delay.toSeconds());
                sleep(delay);
                wait = wait.multipliedBy(2);
            } catch (ResourceAccessException e) {
                throw new Unavailable("Model nie odpowiedział", e);
            }
        }
    }

    private JsonNode answer(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                text.append(part.path("text").asString());
            }
        }
        if (text.isEmpty()) {
            throw new Unavailable("Model nie zwrócił odpowiedzi");
        }
        try {
            return json.readTree(text.toString());
        } catch (RuntimeException e) {
            throw new Unavailable("Model zwrócił nieczytelną odpowiedź", e);
        }
    }

    static Reading reading(JsonNode node) {
        List<Line> lines = new ArrayList<>();
        for (JsonNode line : node.path("lines")) {
            String name = text(line, "name");
            BigDecimal price = line.path("unitNetPrice").isNumber() ? line.path("unitNetPrice").decimalValue() : null;
            if (name == null || price == null) {
                continue;
            }
            lines.add(new Line(name, text(line, "code"), price, line.path("handwrittenCorrection").asBoolean(false),
                    text(line, "note"), line.path("articleNo").asInt(0)));
        }
        return new Reading(text(node, "supplierName"), text(node, "supplierNip"), text(node, "supplierBdo"),
                text(node, "supplierVetNumber"), node.path("supplierNo").asInt(0), text(node, "documentNumber"),
                date(node, "issueDate"), date(node, "shipDate"), lines);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().strip() : null;
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Duration retryDelay(String body, Duration fallback) {
        Matcher matcher = RETRY_DELAY.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            return fallback;
        }
        return Duration.ofMillis(new BigDecimal(matcher.group(1)).movePointRight(3).longValue()).plusSeconds(1);
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

    private static final Map<String, Object> STRING = Map.of("type", "STRING");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "supplierName", STRING,
                    "supplierNip", STRING,
                    "supplierBdo", STRING,
                    "supplierVetNumber", STRING,
                    "supplierNo", Map.of("type", "INTEGER"),
                    "documentNumber", STRING,
                    "issueDate", STRING,
                    "shipDate", STRING,
                    "lines", Map.of("type", "ARRAY", "items", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "name", STRING,
                                    "code", STRING,
                                    "unitNetPrice", Map.of("type", "NUMBER"),
                                    "handwrittenCorrection", Map.of("type", "BOOLEAN"),
                                    "note", STRING,
                                    "articleNo", Map.of("type", "INTEGER")),
                            "required", List.of("name", "unitNetPrice", "handwrittenCorrection", "articleNo")))),
            "required", List.of("supplierName", "supplierNo", "documentNumber", "issueDate", "lines"));
}
