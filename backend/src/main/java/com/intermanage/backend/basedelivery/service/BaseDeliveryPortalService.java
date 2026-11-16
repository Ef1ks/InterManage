package com.intermanage.backend.basedelivery.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.BasePortalDocument;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.service.MeaService;

import tools.jackson.databind.JsonNode;

/**
 * Dokumenty dostaw z bazy prosto z portalu Grupy Muszkieterów (jak „Pobierz BL” w eMEA): lista z ostatnich dni
 * ({@code /documentbl/}) i pobranie bonu ({@code /documentbl/document/}). Logowanie kluczem API sklepu z modułu MEA.
 * Bez transakcji: zapytania do portalu idą przed zapisem, a sam zapis robi {@link BaseDeliveryService#importSheet}.
 */
@Service
public class BaseDeliveryPortalService {

    /** Najwięcej dni wstecz, o które pytamy portal. */
    public static final int MAX_DAYS = 60;

    private final MeaService mea;
    private final MurphysoftClient client;
    private final BaseDeliveryService deliveries;

    public BaseDeliveryPortalService(MeaService mea, MurphysoftClient client, BaseDeliveryService deliveries) {
        this.mea = mea;
        this.client = client;
        this.deliveries = deliveries;
    }

    /** Bony z ostatnich {@code days} dni (0 = dziś), od najnowszego, z oznaczeniem już wgranych. */
    public List<BasePortalDocument> list(UUID storeId, int days) {
        if (days < 0 || days > MAX_DAYS) {
            throw ApiException.badRequest("Podaj od 0 do " + MAX_DAYS + " dni");
        }
        JsonNode body = portal(() -> {
            MeaService.Session session = mea.session(storeId);
            return client.blList(session.shopId(), session.token(), days);
        });
        record Entry(String number, LocalDate date) {
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : body.path("documents")) {
            String number = node.path("name").asString("").strip();
            if (!number.isEmpty() && seen.add(number)) {
                entries.add(new Entry(number, date(node.path("date").asString(""))));
            }
        }
        entries.sort(Comparator.comparing(Entry::date, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Entry::number, Comparator.reverseOrder()));
        Set<String> loaded = deliveries.loadedNumbers(storeId, seen);
        return entries.stream()
                .map(entry -> new BasePortalDocument(entry.number(), entry.date(), loaded.contains(entry.number())))
                .toList();
    }

    /** Pobranie bonu z portalu i zapis jak wgranego pliku (ten sam bon ponownie zastępuje poprzedni). */
    public BaseDeliveryResponse download(UUID storeId, String number, UUID userId) {
        if (!number.matches("[0-9A-Za-z_-]{1,40}")) {
            throw ApiException.badRequest("Nieprawidłowy numer dokumentu");
        }
        JsonNode body = portal(() -> {
            MeaService.Session session = mea.session(storeId);
            return client.blDocument(session.shopId(), session.token(), number);
        });
        return deliveries.importSheet(storeId, sheet(body.path("products")), number + " (portal)", userId);
    }

    /**
     * Linie z portalu jako arkusz dla {@code BaseDeliveryParser}: nagłówek z kluczy linii (w kolejności pierwszego
     * wystąpienia), pod nim wartości. {@code products} to obiekt id → linia (albo tablica linii).
     */
    public static List<List<String>> sheet(JsonNode products) {
        List<JsonNode> lines = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode line : products) {
            if (line.isObject()) {
                lines.add(line);
                for (Map.Entry<String, JsonNode> field : line.properties()) {
                    keys.add(field.getKey());
                }
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Dokument z portalu nie ma żadnej linii");
        }
        List<String> header = List.copyOf(keys);
        List<List<String>> sheet = new ArrayList<>(lines.size() + 1);
        sheet.add(header);
        for (JsonNode line : lines) {
            List<String> row = new ArrayList<>(header.size());
            for (String key : header) {
                JsonNode value = line.path(key);
                row.add(value.isNull() || value.isMissingNode() ? "" : value.asString(""));
            }
            sheet.add(row);
        }
        return sheet;
    }

    private static JsonNode portal(java.util.function.Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.strip().substring(0, 10));
        } catch (DateTimeParseException | IndexOutOfBoundsException exception) {
            return null;
        }
    }
}
