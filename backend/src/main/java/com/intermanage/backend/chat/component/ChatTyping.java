package com.intermanage.backend.chat.component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Kto teraz pisze w rozmowie. Tylko w pamięci (backend jest jeden): przeglądarka zgłasza pisanie co kilka sekund,
 * a wpis gaśnie sam po {@link #TTL}. Telefon nie ma strumienia na żywo, więc dostaje to w odpowiedzi z wiadomościami.
 */
@Component
public class ChatTyping {

    public static final Duration TTL = Duration.ofSeconds(6);

    private final Map<UUID, Map<UUID, Entry>> byConversation = new ConcurrentHashMap<>();

    /** Między ogłoszeniami „pisze…” jednej osoby w rozmowie (częstsze zgłoszenia tylko przedłużają wpis). */
    public static final Duration ANNOUNCE_EVERY = Duration.ofSeconds(3);

    /**
     * Zapisuje pisanie. {@code true}, gdy trzeba o nim powiadomić innych: pierwsze zgłoszenie albo poprzednie
     * ogłoszenie dawniej niż {@link #ANNOUNCE_EVERY}, żeby zasypywanie serwera nie rozsyłało wiadomości do całego sklepu.
     */
    public boolean typing(UUID conversationId, UUID userId) {
        Instant now = Instant.now();
        boolean[] announce = new boolean[1];
        byConversation.computeIfAbsent(conversationId, key -> new ConcurrentHashMap<>())
                .compute(userId, (key, entry) -> {
                    announce[0] = entry == null || !entry.until().isAfter(now)
                            || !entry.announcedAt().plus(ANNOUNCE_EVERY).isAfter(now);
                    return new Entry(now.plus(TTL), announce[0] ? now : entry.announcedAt());
                });
        return announce[0];
    }

    private record Entry(Instant until, Instant announcedAt) {
    }

    public void stopped(UUID conversationId, UUID userId) {
        byConversation.computeIfPresent(conversationId, (key, users) -> {
            users.remove(userId);
            return users.isEmpty() ? null : users;
        });
    }

    /** Piszący teraz, bez pytającego. */
    public List<UUID> typingNow(UUID conversationId, UUID except) {
        Map<UUID, Entry> users = byConversation.get(conversationId);
        if (users == null) {
            return List.of();
        }
        Instant now = Instant.now();
        users.values().removeIf(entry -> entry.until().isBefore(now));
        if (users.isEmpty()) {
            byConversation.remove(conversationId, users);
            return List.of();
        }
        return users.keySet().stream().filter(id -> !id.equals(except)).toList();
    }
}
