package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.intermanage.backend.auth.security.RequestLimits;

/** Limity zapytań z adresu i blokada loginu po nieudanych logowaniach, na przesuwanym zegarze. */
class RequestLimitsTests {

    private final MutableClock clock = new MutableClock();
    private final RequestLimits limits = new RequestLimits(true, 3, 5, 10, 2, Duration.ofMinutes(15), clock);

    @Test
    void loginEndpointsHaveTightLimitPerAddress() {
        for (int i = 0; i < 3; i++) {
            assertThat(limits.acquire(request("POST", "/api/auth/login", "1.1.1.1"))).isZero();
        }
        Duration wait = limits.acquire(request("POST", "/api/auth/accounts/abc/switch", "1.1.1.1"));
        assertThat(wait).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
        // Inny adres i zwykłe API mają własne liczniki.
        assertThat(limits.acquire(request("POST", "/api/auth/login", "2.2.2.2"))).isZero();
        assertThat(limits.acquire(request("GET", "/api/auth/me", "1.1.1.1"))).isZero();

        clock.advance(Duration.ofSeconds(61));
        assertThat(limits.acquire(request("POST", "/api/auth/login", "1.1.1.1"))).isZero();
    }

    @Test
    void apiAndTimeClockHaveSeparateLimits() {
        for (int i = 0; i < 10; i++) {
            assertThat(limits.acquire(request("GET", "/api/stores/x/documents", "3.3.3.3"))).isZero();
        }
        assertThat(limits.acquire(request("GET", "/api/stores/x/documents", "3.3.3.3"))).isPositive();
        for (int i = 0; i < 5; i++) {
            assertThat(limits.acquire(request("POST", "/api/time-clock/punches", "3.3.3.3"))).isZero();
        }
        assertThat(limits.acquire(request("POST", "/api/time-clock/punches", "3.3.3.3"))).isPositive();
    }

    @Test
    void loginIsBlockedAfterFailuresUntilWindowEnds() {
        limits.loginFailed("Anna");
        assertThat(limits.loginBlocked("anna")).isZero();
        limits.loginFailed("anna ");
        assertThat(limits.loginBlocked("ANNA")).isPositive();

        clock.advance(Duration.ofMinutes(15));
        assertThat(limits.loginBlocked("anna")).isZero();

        limits.loginFailed("anna");
        limits.loginSucceeded("anna");
        limits.loginFailed("anna");
        assertThat(limits.loginBlocked("anna")).isZero();
    }

    @Test
    void recognizesAuthEndpoints() {
        assertThat(RequestLimits.isAuth("POST", "/api/auth/mobile/login")).isTrue();
        assertThat(RequestLimits.isAuth("PUT", "/api/auth/pin")).isTrue();
        assertThat(RequestLimits.isAuth("GET", "/api/auth/accounts")).isFalse();
        assertThat(RequestLimits.isAuth("POST", "/api/auth/lock")).isFalse();
    }

    @Test
    void disabledLimitsLetEverythingThrough() {
        RequestLimits off = new RequestLimits(false, 0, 0, 0, 0, Duration.ofMinutes(15), clock);
        assertThat(off.acquire(request("POST", "/api/auth/login", "1.1.1.1"))).isZero();
        off.loginFailed("anna");
        assertThat(off.loginBlocked("anna")).isZero();
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-18T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
