package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.intermanage.backend.dataentry.component.GeminiSuggestionModel;
import com.intermanage.backend.dataentry.component.SuggestionModel;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** Klient Gemini na lokalnym serwerze HTTP: odpowiedź JSON, ponowienie po 503 i rezygnacja po budżecie czekania. */
class GeminiSuggestionModelTests {

    private static final String ANSWER = """
            {"candidates":[{"content":{"parts":[{"text":"{\\"items\\":[{\\"id\\":0,\\"name\\":\\"SER 350G\\",\
            \\"brand\\":\\"SERPOL\\",\\"subfamily\\":\\"254302\\",\\"alternatives\\":[\\"254301\\"],\
            \\"confidence\\":0.92,\\"unit\\":\\"kg\\",\\"net_content\\":0.35}]}"}]}}]}""";
    private static final String OVERLOADED = """
            {"error":{"code":503,"message":"The model is overloaded","details":[{"retryDelay":"0s"}]}}""";

    private final Queue<Object[]> responses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesOverloadedModelAndReadsAnswer() throws IOException {
        responses.add(new Object[] {503, OVERLOADED});
        responses.add(new Object[] {200, ANSWER});
        List<SuggestionModel.Answer> answers = model(Duration.ofSeconds(10)).suggest("zasady", "pozycje");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(answers).singleElement().satisfies(answer -> {
            assertThat(answer.subfamily()).isEqualTo("254302");
            assertThat(answer.alternatives()).containsExactly("254301");
            assertThat(answer.confidence()).isEqualTo(0.92);
            assertThat(answer.netContent()).isEqualByComparingTo("0.35");
        });
    }

    @Test
    void givesUpAfterRetryBudget() throws IOException {
        for (int i = 0; i < 10; i++) {
            responses.add(new Object[] {503, OVERLOADED});
        }
        assertThatThrownBy(() -> model(Duration.ofMillis(1500)).suggest("zasady", "pozycje"))
                .isInstanceOf(SuggestionModel.Unavailable.class)
                .hasMessageContaining("przeciążony");

        responses.clear();
        responses.add(new Object[] {400, "{\"error\":{\"message\":\"bad\"}}"});
        assertThatThrownBy(() -> model(Duration.ofSeconds(10)).suggest("zasady", "pozycje"))
                .isInstanceOf(SuggestionModel.Unavailable.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void withoutKeyIsUnavailable() {
        GeminiSuggestionModel model = new GeminiSuggestionModel(JsonMapper.builder().build(), "http://localhost:1",
                "", "gemini-test", "low", Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(model.available()).isFalse();
        assertThatThrownBy(() -> model.suggest("a", "b")).isInstanceOf(SuggestionModel.Unavailable.class);
    }

    private GeminiSuggestionModel model(Duration retryBudget) throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                Object[] response = responses.poll();
                byte[] body = ((String) response[1]).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders((Integer) response[0], body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }
        return new GeminiSuggestionModel(JsonMapper.builder().build(),
                "http://localhost:" + server.getAddress().getPort(), "klucz", "gemini-test", "low",
                Duration.ofSeconds(5), retryBudget);
    }
}
