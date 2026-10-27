package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.ksef.component.KsefClient;
import com.intermanage.backend.ksef.component.KsefRateLimiter.Operation;
import com.intermanage.backend.ksef.component.KsefRateLimiter;

/** Własne limity zapytań do KSeF: krótkie okna przeczekuje, wyczerpana godzina i blokada 429 kończą od razu. */
class KsefRateLimiterTests {

    private static final String NIP = "5265877635";

    /** Zegar, który przesuwa się tylko przy "spaniu" ogranicznika. */
    private Instant now = Instant.parse("2026-10-26T08:00:00Z");
    private final List<Duration> sleeps = new ArrayList<>();
    private final KsefRateLimiter limiter = new KsefRateLimiter(new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }, duration -> {
        sleeps.add(duration);
        now = now.plus(duration);
    }, true);

    @Test
    void waitsOutShortWindowsAndStopsAtHourlyLimit() {
        // Metadane: 2 na sekundę, 10 na minutę, 16 na godzinę.
        for (int i = 0; i < 16; i++) {
            limiter.acquire(NIP, Operation.METADATA);
        }
        assertThat(sleeps).isNotEmpty();
        // Po 10 zapytaniach w minucie trzeba było przeczekać do końca minuty od pierwszego.
        assertThat(Duration.between(Instant.parse("2026-10-26T08:00:00Z"), now))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(1));

        KsefClient.KsefException limited = assertThrows(KsefClient.KsefException.class,
                () -> limiter.acquire(NIP, Operation.METADATA));
        assertThat(limited.rateLimited()).isTrue();
        assertThat(limited.retryAfter()).isGreaterThan(Duration.ofMinutes(50));

        // Inny NIP i inny rodzaj zapytań mają osobne limity.
        limiter.acquire("1111111111", Operation.METADATA);
        limiter.acquire(NIP, Operation.INVOICE);

        now = now.plus(Duration.ofHours(1));
        limiter.acquire(NIP, Operation.METADATA);
    }

    @Test
    void historySurvivesRestart() {
        List<Instant> saved = new ArrayList<>();
        KsefRateLimiter.History store = new KsefRateLimiter.History() {
            @Override
            public List<Instant> recentCalls(String nip, Operation operation, Instant since) {
                return saved.stream().filter(call -> call.isAfter(since)).toList();
            }

            @Override
            public void recordCall(String nip, Operation operation, Instant at) {
                saved.add(at);
            }

            @Override
            public Instant blockedUntil(String nip) {
                return null;
            }

            @Override
            public void block(String nip, Instant until) {
            }
        };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        KsefRateLimiter restarted = new KsefRateLimiter(clock, duration -> { }, true, store);
        for (int i = 0; i < 16; i++) {
            saved.add(now.minusSeconds(60L * (i + 2)));
        }
        // Nowa instancja (restart backendu) wie o 16 zapytaniach z ostatniej godziny i nie wyśle kolejnego.
        KsefClient.KsefException limited = assertThrows(KsefClient.KsefException.class,
                () -> restarted.acquire(NIP, Operation.METADATA));
        assertThat(limited.rateLimited()).isTrue();
    }

    @Test
    void blockFromKsefStopsAllRequestsOfContext() {
        assertThrows(KsefClient.KsefException.class, () -> limiter.call(NIP, Operation.INVOICE, () -> {
            throw new KsefClient.KsefException("Przekroczony limit", Duration.ofSeconds(30));
        }));

        KsefClient.KsefException blocked = assertThrows(KsefClient.KsefException.class,
                () -> limiter.call(NIP, Operation.METADATA, () -> "nie powinno pójść"));
        assertThat(blocked.retryAfter()).isGreaterThanOrEqualTo(Duration.ofSeconds(30));

        now = now.plus(Duration.ofSeconds(40));
        assertThat(limiter.call(NIP, Operation.METADATA, () -> "ok")).isEqualTo("ok");
    }
}
