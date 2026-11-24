package com.intermanage.backend.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Limity zapytań do API z jednego adresu (za Cloudflare i nginx adres klienta podaje {@code X-Forwarded-For}).
 * Logowanie, PIN i hasło mają ciasny limit, czytniki kart własny, agent sklepu własny na każdy klucz (wiele sklepów
 * może wychodzić przez jeden adres), a reszta API szeroki, bo komputery jednego sklepu
 * wychodzą do internetu z tego samego adresu. Do tego nieudane logowania liczone po loginie: po
 * {@code app.rate-limit.login-failures} w oknie login jest blokowany do końca okna, także z innego adresu.
 */
@Component
public class RequestLimits {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Pattern SWITCH = Pattern.compile("/api/auth/accounts/[^/]+/switch");

    private final boolean enabled;
    private final int authPerMinute;
    private final int timeClockPerMinute;
    private final int apiPerMinute;
    private final int loginFailures;
    private final Duration loginFailureWindow;
    private final RateLimiter limiter;

    @Autowired
    public RequestLimits(@Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.auth-per-minute:30}") int authPerMinute,
            @Value("${app.rate-limit.time-clock-per-minute:300}") int timeClockPerMinute,
            @Value("${app.rate-limit.api-per-minute:3000}") int apiPerMinute,
            @Value("${app.rate-limit.login-failures:10}") int loginFailures,
            @Value("${app.rate-limit.login-failure-window:15m}") Duration loginFailureWindow) {
        this(enabled, authPerMinute, timeClockPerMinute, apiPerMinute, loginFailures, loginFailureWindow,
                Clock.systemUTC());
    }

    public RequestLimits(boolean enabled, int authPerMinute, int timeClockPerMinute, int apiPerMinute, int loginFailures,
            Duration loginFailureWindow, Clock clock) {
        this.enabled = enabled;
        this.authPerMinute = authPerMinute;
        this.timeClockPerMinute = timeClockPerMinute;
        this.apiPerMinute = apiPerMinute;
        this.loginFailures = loginFailures;
        this.loginFailureWindow = loginFailureWindow;
        this.limiter = new RateLimiter(clock);
    }

    /** Liczy żądanie API; zwraca czas oczekiwania, gdy adres przekroczył limit swojej reguły. */
    public Duration acquire(HttpServletRequest request) {
        if (!enabled) {
            return Duration.ZERO;
        }
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();
        if (isAuth(request.getMethod(), path)) {
            return limiter.acquire("auth:" + ip, authPerMinute, MINUTE);
        }
        if (path.startsWith("/api/time-clock/")) {
            return limiter.acquire("clock:" + ip, timeClockPerMinute, MINUTE);
        }
        // Agent sklepu: limit czytników kart na klucz, a na adres szeroki (najpierw, żeby zmyślone klucze go nie omijały).
        if (path.startsWith("/api/store-agent/")) {
            Duration wait = limiter.acquire("agents:" + ip, apiPerMinute, MINUTE);
            return wait.isZero() ? limiter.acquire("agent:" + agentKey(request, ip), timeClockPerMinute, MINUTE) : wait;
        }
        return limiter.acquire("api:" + ip, apiPerMinute, MINUTE);
    }

    /** Ile jeszcze login jest zablokowany po nieudanych logowaniach ({@link Duration#ZERO} = nie jest). */
    public Duration loginBlocked(String username) {
        return enabled ? limiter.check(loginKey(username), loginFailures) : Duration.ZERO;
    }

    public void loginFailed(String username) {
        if (enabled) {
            limiter.record(loginKey(username), loginFailureWindow);
        }
    }

    public void loginSucceeded(String username) {
        limiter.reset(loginKey(username));
    }

    public static boolean isAuth(String method, String path) {
        return switch (method) {
            case "POST" -> path.equals("/api/auth/login") || path.equals("/api/auth/mobile/login")
                    || path.equals("/api/auth/activate") || path.equals("/api/auth/password/setup")
                    || path.equals("/api/auth/accounts") || SWITCH.matcher(path).matches();
            case "PUT" -> path.equals("/api/auth/pin") || path.equals("/api/auth/pin/required")
                    || path.equals("/api/auth/password");
            default -> false;
        };
    }

    /** Skrót klucza z nagłówka, żeby sam klucz nie leżał w licznikach; bez klucza adres. */
    private static String agentKey(HttpServletRequest request, String ip) {
        String authorization = request.getHeader("Authorization");
        return authorization == null || authorization.isBlank() ? ip
                : UUID.nameUUIDFromBytes(authorization.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String loginKey(String username) {
        return "login:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }
}
