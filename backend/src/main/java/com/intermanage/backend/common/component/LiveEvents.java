package com.intermanage.backend.common.component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wiadomości na żywo dla przeglądarek (Server-Sent Events, {@code GET /api/stores/{storeId}/live}). Backend jest jeden,
 * więc połączenia są w pamięci, osobno dla każdego sklepu. Wiadomość to sam sygnał ({@code topic} i kilka pól bez
 * danych wrażliwych): przeglądarka odświeża po nim swoje zapytania. Wysyłamy ją po zatwierdzeniu transakcji, żeby
 * przeglądarka, która od razu zapyta, widziała już zmianę.
 *
 * <p>Co 20 s każde połączenie dostaje komentarz {@code :ping} (Cloudflare zrywa bezczynne po 100 s), a przy okazji
 * sprawdzamy, czy jego właściciel dalej ma dostęp ({@link Subscription#stillValid}): wygasły token, konto w tle albo
 * odebrany sklep zamykają strumień.
 */
@Component
public class LiveEvents {

    private static final Logger log = LoggerFactory.getLogger(LiveEvents.class);

    /** Jedno połączenie: kto słucha i do kiedy (ważność tokenu). */
    public record Subscription(UUID storeId, UUID userId, Instant expiresAt, SseEmitter emitter,
            BooleanSupplier stillValid) {
    }

    private final Map<UUID, Set<Subscription>> byStore = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public LiveEvents(JsonMapper json) {
        this.json = json;
    }

    /** Nowe połączenie; zamyka się samo po wygaśnięciu tokenu (najpóźniej przy kolejnym pingu). */
    public SseEmitter subscribe(UUID storeId, UUID userId, Instant expiresAt, BooleanSupplier stillValid) {
        long timeout = Math.max(1_000, expiresAt.toEpochMilli() - System.currentTimeMillis());
        SseEmitter emitter = new SseEmitter(timeout);
        Subscription subscription = new Subscription(storeId, userId, expiresAt, emitter, stillValid);
        Set<Subscription> set = byStore.computeIfAbsent(storeId, key -> ConcurrentHashMap.newKeySet());
        set.add(subscription);
        Runnable remove = () -> set.remove(subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(error -> remove.run());
        try {
            // Pierwsza wiadomość od razu: przeglądarka wie, że połączenie działa (i nginx wysyła nagłówki).
            emitter.send(SseEmitter.event().comment("connected"));
            emitter.send(SseEmitter.event().reconnectTime(5_000));
        } catch (IOException | IllegalStateException e) {
            remove.run();
        }
        return emitter;
    }

    /** Wysyła wiadomość wszystkim połączeniom sklepu, po zatwierdzeniu bieżącej transakcji (bez niej od razu). */
    public void publish(UUID storeId, String topic, Map<String, ?> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("topic", topic);
        message.putAll(fields);
        afterCommit(() -> send(storeId, message));
    }

    /** Zamyka połączenia osoby (konto przeszło do tła albo się wylogowało); przeglądarka połączy się od nowa. */
    public void closeUser(UUID userId) {
        byStore.values().forEach(set -> set.stream().filter(sub -> sub.userId().equals(userId)).toList()
                .forEach(sub -> {
                    set.remove(sub);
                    sub.emitter().complete();
                }));
    }

    /** Liczba połączeń sklepu (testy, diagnostyka). */
    public int connections(UUID storeId) {
        Set<Subscription> set = byStore.get(storeId);
        return set == null ? 0 : set.size();
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    void ping() {
        Instant now = Instant.now();
        byStore.values().forEach(set -> set.forEach(sub -> {
            boolean valid;
            try {
                valid = sub.expiresAt().isAfter(now) && sub.stillValid().getAsBoolean();
            } catch (RuntimeException e) {
                valid = false;
            }
            if (!valid) {
                set.remove(sub);
                sub.emitter().complete();
                return;
            }
            try {
                sub.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                set.remove(sub);
            }
        }));
    }

    private void send(UUID storeId, Map<String, Object> message) {
        Set<Subscription> set = byStore.get(storeId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String data;
        try {
            data = json.writeValueAsString(message);
        } catch (RuntimeException e) {
            log.warn("Nie udało się przygotować wiadomości na żywo {}", message.get("topic"), e);
            return;
        }
        for (Subscription sub : set) {
            try {
                sub.emitter().send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                set.remove(sub);
            }
        }
    }

    /** Uruchamia po zatwierdzeniu bieżącej transakcji albo od razu, gdy jej nie ma. */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
