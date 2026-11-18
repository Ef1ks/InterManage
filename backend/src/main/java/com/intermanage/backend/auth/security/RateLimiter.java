package com.intermanage.backend.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Licznik zdarzeń w stałych oknach czasu, osobno dla każdego klucza (np. reguła + adres IP). Trzymany w pamięci,
 * bo produkcja ma jeden backend; restart zeruje liczniki. Stare okna są sprzątane, gdy kluczy robi się dużo.
 */
public class RateLimiter {

    /** Po tylu kluczach przy dodawaniu kolejnego usuwamy okna, które już minęły. */
    private static final int CLEANUP_ABOVE = 20_000;

    private record Window(Instant start, Duration length, int count) {
        boolean over(Instant now) {
            return !now.isBefore(start.plus(length));
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Liczy zdarzenie i zwraca, ile trzeba poczekać, gdy limit okna został przekroczony ({@link Duration#ZERO} =
     * zdarzenie się mieści).
     */
    public Duration acquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        cleanUp(now);
        Window current = windows.compute(key, (k, existing) -> existing == null || existing.over(now)
                ? new Window(now, window, 1)
                : new Window(existing.start(), existing.length(), existing.count() + 1));
        return current.count() <= limit ? Duration.ZERO : waitOf(current, now);
    }

    /** Czy klucz przekroczył limit, bez liczenia zdarzenia; zwraca czas oczekiwania jak {@link #acquire}. */
    public Duration check(String key, int limit) {
        Instant now = clock.instant();
        Window current = windows.get(key);
        if (current == null || current.over(now) || current.count() < limit) {
            return Duration.ZERO;
        }
        return waitOf(current, now);
    }

    /** Liczy zdarzenie bez sprawdzania limitu (np. nieudane logowanie). */
    public void record(String key, Duration window) {
        acquire(key, Integer.MAX_VALUE, window);
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private static Duration waitOf(Window window, Instant now) {
        Duration left = Duration.between(now, window.start().plus(window.length()));
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    private void cleanUp(Instant now) {
        if (windows.size() > CLEANUP_ABOVE) {
            windows.values().removeIf(window -> window.over(now));
        }
    }
}
