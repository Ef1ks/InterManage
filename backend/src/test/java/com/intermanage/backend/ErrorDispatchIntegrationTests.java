package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nieobsłużony wyjątek u zalogowanego użytkownika to 500, a nie 401: serwer przekazuje go do /error drugim
 * przejściem, w którym filtr JWT nie działa, a front przy 401 wylogowuje. MockMvc tego przejścia nie robi,
 * więc test idzie przez prawdziwy serwer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.bootstrap.owner-username=wlasciciel",
        "app.bootstrap.owner-password=haslo-wlasciciela",
        "app.ksef.sync-enabled=false"
})
@Import({TestcontainersConfiguration.class, RecordingAccountMailer.Config.class,
        ErrorDispatchIntegrationTests.FailingController.class})
class ErrorDispatchIntegrationTests {

    @LocalServerPort
    int port;

    @Test
    void serverErrorDoesNotLogOut() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> csrf = client.send(request("/api/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = cookieValue(csrf, "XSRF-TOKEN");

        HttpResponse<String> login = client.send(request("/api/auth/login")
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", xsrf)
                        .header("Cookie", "XSRF-TOKEN=" + xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"wlasciciel\",\"password\":\"haslo-wlasciciela\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String token = cookieValue(login, "AUTH_TOKEN");

        HttpResponse<String> failing = client.send(request("/api/test/failing")
                        .header("Cookie", "AUTH_TOKEN=" + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(failing.statusCode()).isEqualTo(500);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(name + "="))
                .map(header -> header.substring(name.length() + 1, header.indexOf(';')))
                .filter(value -> !value.isEmpty())
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @TestConfiguration
    @RestController
    static class FailingController {

        @GetMapping("/api/test/failing")
        String failing() {
            throw new IllegalStateException("Błąd testowy");
        }
    }
}
