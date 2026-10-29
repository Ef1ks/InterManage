package com.intermanage.backend.ksef.component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pilnuje limitów API KSeF, zanim KSeF sam zablokuje dostęp (blokady za powtórne przekroczenia się wydłużają).
 * KSeF liczy zapytania na parę kontekst (NIP) + adres IP w oknach przesuwnych: sekunda, minuta i godzina naraz.
 * Wszystkie sklepy aplikacji wychodzą z jednego IP, więc liczymy po NIP-ie, z zapasem poniżej limitów KSeF.
 * Krótkie okna (sekunda, minuta) przeczekujemy, wyczerpana godzina albo blokada po 429 kończy się od razu
 * {@link KsefClient.KsefException} z czasem, po którym można spróbować znowu. Historia jest w pamięci i w bazie
 * ({@link History}): po restarcie limiter wczytuje zapytania z ostatniej godziny, bo KSeF liczy je dalej.
 */
@Component
public class KsefRateLimiter {

    /** Najdłużej czekamy na wolne miejsce w ramach jednego zapytania; dłużej = zapytanie odłożone na później. */
    static final Duration MAX_WAIT = Duration.ofSeconds(65);
    /** Zapas po blokadzie z KSeF (Retry-After), żeby pierwsze zapytanie po niej nie trafiło jeszcze w blokadę. */
    private static final Duration BLOCK_MARGIN = Duration.ofSeconds(5);

    /**
     * Rodzaje zapytań z własnymi limitami. Limity KSeF (produkcja): metadane 8/s, 16/min, 20/h; pobranie faktury
     * 8/s, 16/min, 64/h; pozostałe 10/s, 30/min, 120/h. Logowanie to kilka zapytań, więc liczymy je jako jedno
     * z niskim limitem.
     */
    public enum Operation {
        METADATA("wyszukiwanie faktur", 2, 10, 16),
        INVOICE("pobieranie faktur", 2, 10, 50),
        AUTH("logowanie", 1, 4, 15);

        final String label;
        final int perSecond;
        final int perMinute;
        final int perHour;

        Operation(String label, int perSecond, int perMinute, int perHour) {
            this.label = label;
            this.perSecond = perSecond;
            this.perMinute = perMinute;
            this.perHour = perHour;
        }
    }

    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Trwała historia zapytań i blokad (w aplikacji baza: {@link KsefRateHistory}). */
    public interface History {

        List<Instant> recentCalls(String nip, Operation operation, Instant since);

        void recordCall(String nip, Operation operation, Instant at);

        Instant blockedUntil(String nip);

        void block(String nip, Instant until);

        /** Tylko pamięć (testy jednostkowe). */
        History NONE = new History() {
            @Override
            public List<Instant> recentCalls(String nip, Operation operation, Instant since) {
                return List.of();
            }

            @Override
            public void recordCall(String nip, Operation operation, Instant at) {
            }

            @Override
            public Instant blockedUntil(String nip) {
                return null;
            }

            @Override
            public void block(String nip, Instant until) {
            }
        };
    }

    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Deque<Instant>> history = new HashMap<>();
    private final Map<String, Instant> blockedUntil = new HashMap<>();
    /** Klucze (historia zapytań) i NIP-y (blokady) już wczytane z {@link History} od startu aplikacji. */
    private final Set<String> loaded = new HashSet<>();

    private final boolean enabled;
    private final History store;

    @Autowired
    public KsefRateLimiter(@Value("${app.ksef.rate-limit-enabled}") boolean enabled, History store) {
        this(Clock.systemUTC(), Thread::sleep, enabled, store);
    }

    public KsefRateLimiter(Clock clock, Sleeper sleeper, boolean enabled) {
        this(clock, sleeper, enabled, History.NONE);
    }

    public KsefRateLimiter(Clock clock, Sleeper sleeper, boolean enabled, History store) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.enabled = enabled;
        this.store = store;
    }

    /** Zapytanie do KSeF w kontekście {@code nip} w granicach limitów; 429 z KSeF wstrzymuje cały kontekst. */
    public <T> T call(String nip, Operation operation, Supplier<T> request) {
        acquire(nip, operation);
        try {
            return request.get();
        } catch (KsefClient.KsefException e) {
            if (e.rateLimited()) {
                block(nip, e.retryAfter());
            }
            throw e;
        }
    }

    /** Czeka na wolne miejsce i je zajmuje; gdy trzeba by czekać dłużej niż {@link #MAX_WAIT}, rzuca wyjątek. */
    public void acquire(String nip, Operation operation) {
        if (!enabled) {
            return;
        }
        while (true) {
            Duration wait;
            synchronized (this) {
                Instant now = clock.instant();
                Instant blocked = blocked(nip);
                if (blocked != null && blocked.isAfter(now)) {
                    throw limited(operation, Duration.between(now, blocked), "KSeF wstrzymał zapytania");
                }
                Deque<Instant> calls = calls(nip, operation, now);
                wait = max(waitFor(calls, now, Duration.ofSeconds(1), operation.perSecond),
                        waitFor(calls, now, Duration.ofMinutes(1), operation.perMinute),
                        waitFor(calls, now, Duration.ofHours(1), operation.perHour));
                if (wait.isZero()) {
                    calls.addLast(now);
                    store.recordCall(nip, operation, now);
                    return;
                }
                if (wait.compareTo(MAX_WAIT) > 0) {
                    throw limited(operation, wait, "Wyczerpany godzinowy limit KSeF");
                }
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KsefClient.KsefException("Przerwano czekanie na limit KSeF", null);
            }
        }
    }

    /**
     * Ile zapytań tego rodzaju zostało w bieżącej godzinie (0 w czasie blokady z KSeF) - zadania w tle zostawiają
     * dzięki temu zapas na zapytania ludzi.
     */
    public synchronized int remaining(String nip, Operation operation) {
        if (!enabled) {
            return operation.perHour;
        }
        Instant now = clock.instant();
        Instant blocked = blocked(nip);
        if (blocked != null && blocked.isAfter(now)) {
            return 0;
        }
        return Math.max(0, operation.perHour - calls(nip, operation, now).size());
    }

    /** Koniec blokady z KSeF ({@code null} = brak); pierwsze sięgnięcie po starcie wczytuje ją z {@link History}. */
    private Instant blocked(String nip) {
        if (loaded.add("block|" + nip)) {
            Instant saved = store.blockedUntil(nip);
            if (saved != null) {
                blockedUntil.merge(nip, saved, (current, next) -> next.isAfter(current) ? next : current);
            }
        }
        return blockedUntil.get(nip);
    }

    /** Zapytania z ostatniej godziny; pierwsze sięgnięcie po starcie wczytuje je z {@link History}. */
    private Deque<Instant> calls(String nip, Operation operation, Instant now) {
        String key = nip + "|" + operation;
        Deque<Instant> calls = history.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (loaded.add(key)) {
            // Pierwsze zapytanie po starcie: zapytania sprzed restartu wciąż liczą się w KSeF.
            store.recentCalls(nip, operation, now.minus(Duration.ofHours(1))).forEach(calls::addFirst);
            List<Instant> sorted = calls.stream().sorted().toList();
            calls.clear();
            calls.addAll(sorted);
        }
        while (!calls.isEmpty() && !calls.peekFirst().isAfter(now.minus(Duration.ofHours(1)))) {
            calls.pollFirst();
        }
        return calls;
    }

    /** Blokada z KSeF (HTTP 429): do jej końca nie wysyłamy z tego kontekstu żadnych zapytań. */
    public synchronized void block(String nip, Duration retryAfter) {
        Instant until = clock.instant().plus(retryAfter).plus(BLOCK_MARGIN);
        Instant merged = blockedUntil.merge(nip, until, (current, next) -> next.isAfter(current) ? next : current);
        store.block(nip, merged);
    }

    /** Ile czekać, aż w oknie {@code window} zwolni się miejsce (najstarsze zapytanie z okna z niego wypadnie). */
    private static Duration waitFor(Deque<Instant> calls, Instant now, Duration window, int limit) {
        Instant start = now.minus(window);
        List<Instant> inWindow = calls.stream().filter(call -> call.isAfter(start)).toList();
        if (inWindow.size() < limit) {
            return Duration.ZERO;
        }
        // Miejsce zwolni się, gdy z okna wypadnie tyle najstarszych zapytań, żeby zostało ich limit - 1.
        Instant releasing = inWindow.get(inWindow.size() - limit);
        return Duration.between(start, releasing).plusMillis(1);
    }

    private static Duration max(Duration... durations) {
        Duration max = Duration.ZERO;
        for (Duration duration : durations) {
            if (duration.compareTo(max) > 0) {
                max = duration;
            }
        }
        return max;
    }

    private static KsefClient.KsefException limited(Operation operation, Duration retryAfter, String reason) {
        long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
        return new KsefClient.KsefException(reason + " (" + operation.label + ") - spróbuj ponownie za ok. "
                + minutes + " min", retryAfter);
    }
}
