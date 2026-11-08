package com.intermanage.backend.gus.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.intermanage.backend.gus.component.GusClient;
import com.intermanage.backend.gus.dto.GusCompany;
import com.intermanage.backend.store.entity.Store;

/**
 * Dane firmy z GUS z pamięcią wyników: firma (albo jej brak) na dobę, błąd GUS na 10 minut, żeby ekran, który
 * podpowiada dane firmy przy każdym otwarciu, nie czekał za każdym razem na niedziałający GUS.
 */
@Service
public class GusService {

    private static final Logger log = LoggerFactory.getLogger(GusService.class);
    private static final Duration FOUND_TTL = Duration.ofHours(24);
    private static final Duration FAILED_TTL = Duration.ofMinutes(10);

    private record Entry(Optional<GusCompany> company, String error, Instant until) {
    }

    private final GusClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public GusService(GusClient client) {
        this.client = client;
    }

    /** Firma o tym NIP-ie (10 cyfr); pusta, gdy GUS jej nie zna. Błąd GUS: {@link GusClient.Unavailable}. */
    public Optional<GusCompany> find(String nip) {
        Entry entry = cache.get(nip);
        if (entry == null || Instant.now().isAfter(entry.until())) {
            entry = load(nip);
            cache.put(nip, entry);
        }
        if (entry.error() != null) {
            throw new GusClient.Unavailable(entry.error());
        }
        return entry.company();
    }

    /**
     * Firma sklepu po jego NIP-ie do podpowiedzi na wydrukach (wnioski, pieczątka): pusta bez NIP-u, bez klucza GUS
     * i przy błędzie GUS, bo podpowiedź nie może zablokować ekranu.
     */
    public Optional<GusCompany> ofStore(Store store) {
        if (store.getNip() == null || !client.configured()) {
            return Optional.empty();
        }
        try {
            return find(store.getNip());
        } catch (GusClient.Unavailable exception) {
            return Optional.empty();
        }
    }

    private Entry load(String nip) {
        try {
            return new Entry(client.findByNip(nip), null, Instant.now().plus(FOUND_TTL));
        } catch (GusClient.Unavailable exception) {
            log.warn("GUS dla NIP-u {}: {}", nip, exception.getMessage());
            return new Entry(Optional.empty(), exception.getMessage(), Instant.now().plus(FAILED_TTL));
        }
    }
}
