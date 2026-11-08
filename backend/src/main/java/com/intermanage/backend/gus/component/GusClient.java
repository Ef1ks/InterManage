package com.intermanage.backend.gus.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.intermanage.backend.gus.dto.GusCompany;

/**
 * Wyszukiwarka REGON (BIR 1.1, SOAP 1.2 z WS-Addressing): {@code Zaloguj} kluczem daje identyfikator sesji (nagłówek
 * {@code sid}, ważny 60 min, trzymamy go 50 min), {@code DaneSzukajPodmioty} z NIP-em zwraca XML firmy zakodowany
 * jako tekst w odpowiedzi. Odpowiedź przychodzi jako MTOM (multipart), więc bierzemy z niej samą kopertę SOAP.
 * Wygasła sesja daje pusty wynik: wtedy logujemy się od nowa i pytamy jeszcze raz.
 */
@Component
public class GusClient {

    /** Usługa niedostępna: brak klucza, odrzucony klucz, błąd sieci albo GUS. Komunikat idzie do użytkownika. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GusClient.class);
    private static final String ACTION = "http://CIS/BIR/PUBL/2014/07/IUslugaBIRzewnPubl/";
    private static final Duration SESSION_TTL = Duration.ofMinutes(50);

    private final HttpClient http;
    private final String url;
    private final String apiKey;
    private final Duration timeout;

    private String sessionId;
    private Instant sessionUntil = Instant.MIN;

    public GusClient(@Value("${app.gus.url}") String url, @Value("${app.gus.api-key:}") String apiKey,
            @Value("${app.gus.timeout}") Duration timeout) {
        this.url = url;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Firma o tym NIP-ie (10 cyfr); pusta, gdy GUS jej nie zna. Przy kilku wpisach najpierw działające. */
    public synchronized Optional<GusCompany> findByNip(String nip) {
        if (!configured()) {
            throw new Unavailable("Pobieranie danych z GUS nie jest skonfigurowane (brak klucza GUS_API_KEY)");
        }
        String result = search(nip, session(false));
        if (result.isBlank()) {
            result = search(nip, session(true));
        }
        if (result.isBlank()) {
            throw new Unavailable("GUS nie odpowiedział na zapytanie. Spróbuj ponownie za chwilę");
        }
        return parseCompanies(result).stream()
                .min(Comparator.comparing((GusCompany company) -> company.endedAt() != null));
    }

    private String session(boolean fresh) {
        if (!fresh && sessionId != null && Instant.now().isBefore(sessionUntil)) {
            return sessionId;
        }
        String body = """
                <ns:Zaloguj><ns:pKluczUzytkownika>%s</ns:pKluczUzytkownika></ns:Zaloguj>""".formatted(escape(apiKey));
        String id = resultOf(call("Zaloguj", body, null), "ZalogujResult").trim();
        if (id.isEmpty()) {
            sessionId = null;
            throw new Unavailable("GUS odrzucił klucz GUS_API_KEY");
        }
        sessionId = id;
        sessionUntil = Instant.now().plus(SESSION_TTL);
        return id;
    }

    private String search(String nip, String sid) {
        String body = """
                <ns:DaneSzukajPodmioty><ns:pParametryWyszukiwania><dat:Nip>%s</dat:Nip></ns:pParametryWyszukiwania>\
                </ns:DaneSzukajPodmioty>""".formatted(escape(nip));
        return resultOf(call("DaneSzukajPodmioty", body, sid), "DaneSzukajPodmiotyResult");
    }

    private String call(String operation, String body, String sid) {
        String envelope = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:ns="http://CIS/BIR/PUBL/2014/07" \
                xmlns:dat="http://CIS/BIR/PUBL/2014/07/DataContract">\
                <soap:Header xmlns:wsa="http://www.w3.org/2005/08/addressing"><wsa:To>%s</wsa:To>\
                <wsa:Action>%s%s</wsa:Action></soap:Header><soap:Body>%s</soap:Body></soap:Envelope>"""
                .formatted(escape(url), ACTION, operation, body);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
        if (sid != null) {
            request.header("sid", sid);
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("GUS {}: HTTP {}", operation, response.statusCode());
                throw new Unavailable("GUS odpowiedział błędem (HTTP " + response.statusCode() + ")");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            log.warn("GUS {}: {}", operation, exception.toString());
            throw new Unavailable("Brak połączenia z GUS. Spróbuj ponownie za chwilę");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Przerwano zapytanie do GUS");
        }
    }

    /** Tekst elementu wyniku z koperty SOAP (odpowiedź MTOM ma kopertę między nagłówkami części). */
    static String resultOf(String response, String element) {
        int start = response.indexOf("<s:Envelope");
        int end = response.indexOf("</s:Envelope>");
        if (start < 0 || end < 0) {
            throw new Unavailable("Nieczytelna odpowiedź z GUS");
        }
        Document document = parse(response.substring(start, end + "</s:Envelope>".length()));
        NodeList nodes = document.getElementsByTagNameNS("*", element);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    /** Wpisy z XML wyniku wyszukiwania; wpis z {@code ErrorCode} (np. 4: nie znaleziono) jest pomijany. */
    static List<GusCompany> parseCompanies(String xml) {
        NodeList entries = parse(xml).getElementsByTagName("dane");
        List<GusCompany> companies = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (text(entry, "ErrorCode") != null) {
                continue;
            }
            String city = text(entry, "Miejscowosc");
            String streetName = text(entry, "Ulica");
            String number = join("/", text(entry, "NrNieruchomosci"), text(entry, "NrLokalu"));
            String street = join(" ", streetName != null ? streetName : city, number);
            String postalCode = text(entry, "KodPocztowy");
            String postCity = text(entry, "MiejscowoscPoczty");
            // Pod kodem stoi poczta (Swadzim ma pocztę 62-080 Tarnowo Podgórne), a inna miejscowość idzie przed nim.
            String mailCity = postCity != null ? postCity : city;
            String locality = streetName != null && city != null && !city.equalsIgnoreCase(mailCity) ? city : null;
            String address = join(", ", street, locality, join(" ", postalCode, mailCity));
            companies.add(new GusCompany(text(entry, "Nip"), text(entry, "Regon"), text(entry, "Nazwa"), street,
                    postalCode, city, address, date(text(entry, "DataZakonczeniaDzialalnosci"))));
        }
        return companies;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new Unavailable("Nieczytelna odpowiedź z GUS");
        }
    }

    private static String text(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                String value = element.getTextContent().strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.strip());
            }
        }
        return present.isEmpty() ? null : String.join(separator, present);
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
