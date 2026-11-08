package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.intermanage.backend.gus.component.GusClient;
import com.intermanage.backend.gus.dto.GusCompany;
import com.sun.net.httpserver.HttpServer;

/** Klient GUS (BIR 1.1) na lokalnym serwerze HTTP z odpowiedziami MTOM jak z wyszukiwarkaregon.stat.gov.pl. */
class GusClientTests {

    private static final String SCA = """
            &lt;root&gt;&#xD;
              &lt;dane&gt;&#xD;
                &lt;Regon&gt;631030739&lt;/Regon&gt;&#xD;
                &lt;Nip&gt;7821977018&lt;/Nip&gt;&#xD;
                &lt;StatusNip /&gt;&#xD;
                &lt;Nazwa&gt;"SCA PR POLSKA" SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ&lt;/Nazwa&gt;&#xD;
                &lt;Miejscowosc&gt;Swadzim&lt;/Miejscowosc&gt;&#xD;
                &lt;KodPocztowy&gt;62-080&lt;/KodPocztowy&gt;&#xD;
                &lt;Ulica&gt;ul. św. Mikołaja&lt;/Ulica&gt;&#xD;
                &lt;NrNieruchomosci&gt;5&lt;/NrNieruchomosci&gt;&#xD;
                &lt;NrLokalu /&gt;&#xD;
                &lt;Typ&gt;P&lt;/Typ&gt;&#xD;
                &lt;DataZakonczeniaDzialalnosci /&gt;&#xD;
                &lt;MiejscowoscPoczty&gt;Tarnowo Podgórne&lt;/MiejscowoscPoczty&gt;&#xD;
              &lt;/dane&gt;&#xD;
            &lt;/root&gt;""";
    private static final String VILLAGE = """
            &lt;root&gt;&lt;dane&gt;&lt;Regon&gt;123456785&lt;/Regon&gt;&lt;Nip&gt;9291243024&lt;/Nip&gt;\
            &lt;Nazwa&gt;PIEKARNIA JAN KOWALSKI&lt;/Nazwa&gt;&lt;Miejscowosc&gt;Mała Wieś&lt;/Miejscowosc&gt;\
            &lt;KodPocztowy&gt;62-200&lt;/KodPocztowy&gt;&lt;Ulica /&gt;&lt;NrNieruchomosci&gt;12&lt;/NrNieruchomosci&gt;\
            &lt;NrLokalu&gt;3&lt;/NrLokalu&gt;&lt;MiejscowoscPoczty&gt;Gniezno&lt;/MiejscowoscPoczty&gt;\
            &lt;DataZakonczeniaDzialalnosci /&gt;&lt;/dane&gt;&lt;/root&gt;""";
    private static final String NOT_FOUND = """
            &lt;root&gt;&lt;dane&gt;&lt;ErrorCode&gt;4&lt;/ErrorCode&gt;\
            &lt;ErrorMessagePl&gt;Nie znaleziono podmiotu dla podanych kryteriów wyszukiwania.&lt;/ErrorMessagePl&gt;\
            &lt;/dane&gt;&lt;/root&gt;""";

    private final List<String> searchResults = new CopyOnWriteArrayList<>();
    private final List<String> sessionIds = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private String loginResult = "sesja1";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void findsCompanyByNip() throws IOException {
        searchResults.add(SCA);
        Optional<GusCompany> company = client("klucz").findByNip("7821977018");

        assertThat(company).hasValueSatisfying(found -> {
            assertThat(found.name()).isEqualTo("\"SCA PR POLSKA\" SPÓŁKA Z OGRANICZONĄ ODPOWIEDZIALNOŚCIĄ");
            assertThat(found.regon()).isEqualTo("631030739");
            assertThat(found.street()).isEqualTo("ul. św. Mikołaja 5");
            assertThat(found.postalCode()).isEqualTo("62-080");
            assertThat(found.city()).isEqualTo("Swadzim");
            assertThat(found.address()).isEqualTo("ul. św. Mikołaja 5, Swadzim, 62-080 Tarnowo Podgórne");
            assertThat(found.endedAt()).isNull();
        });
        assertThat(sessionIds).containsExactly("sesja1");
    }

    @Test
    void villageWithoutStreetUsesLocalityAndPostOffice() throws IOException {
        searchResults.add(VILLAGE);
        GusCompany company = client("klucz").findByNip("9291243024").orElseThrow();

        assertThat(company.street()).isEqualTo("Mała Wieś 12/3");
        assertThat(company.city()).isEqualTo("Mała Wieś");
        assertThat(company.address()).isEqualTo("Mała Wieś 12/3, 62-200 Gniezno");
    }

    @Test
    void unknownNipIsEmptyAndExpiredSessionLogsInAgain() throws IOException {
        GusClient client = client("klucz");
        searchResults.add(NOT_FOUND);
        assertThat(client.findByNip("1111111111")).isEmpty();
        assertThat(logins.get()).isEqualTo(1);

        // Sesja wygasła po stronie GUS: pusty wynik, ponowne logowanie i drugie zapytanie.
        loginResult = "sesja2";
        searchResults.add("");
        searchResults.add(SCA);
        assertThat(client.findByNip("7821977018")).isPresent();
        assertThat(logins.get()).isEqualTo(2);
        assertThat(sessionIds).containsExactly("sesja1", "sesja1", "sesja2");
    }

    @Test
    void rejectedKeyAndMissingKeyAreUnavailable() throws IOException {
        loginResult = "";
        assertThatThrownBy(() -> client("zly").findByNip("7821977018"))
                .isInstanceOf(GusClient.Unavailable.class)
                .hasMessageContaining("odrzucił klucz");

        assertThatThrownBy(() -> new GusClient("http://localhost:1", "", Duration.ofSeconds(1)).findByNip("7821977018"))
                .isInstanceOf(GusClient.Unavailable.class)
                .hasMessageContaining("GUS_API_KEY");
    }

    private GusClient client(String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (request.contains("IUslugaBIRzewnPubl/Zaloguj")) {
                logins.incrementAndGet();
                body = "<ZalogujResponse xmlns=\"http://CIS/BIR/PUBL/2014/07\"><ZalogujResult>" + loginResult
                        + "</ZalogujResult></ZalogujResponse>";
            } else {
                sessionIds.add(exchange.getRequestHeaders().getFirst("sid"));
                String result = searchResults.isEmpty() ? "" : searchResults.removeFirst();
                body = "<DaneSzukajPodmiotyResponse xmlns=\"http://CIS/BIR/PUBL/2014/07\"><DaneSzukajPodmiotyResult>"
                        + result + "</DaneSzukajPodmiotyResult></DaneSzukajPodmiotyResponse>";
            }
            byte[] bytes = mtom(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "multipart/related; type=\"application/xop+xml\"");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GusClient("http://localhost:" + server.getAddress().getPort() + "/", key, Duration.ofSeconds(5));
    }

    private static String mtom(String body) {
        return "\r\n--uuid:1+id=1\r\nContent-ID: <http://tempuri.org/0>\r\nContent-Transfer-Encoding: 8bit\r\n"
                + "Content-Type: application/xop+xml;charset=utf-8;type=\"application/soap+xml\"\r\n\r\n"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://www.w3.org/2005/08/addressing\"><s:Header/><s:Body>" + body
                + "</s:Body></s:Envelope>\r\n--uuid:1+id=1--\r\n";
    }
}
