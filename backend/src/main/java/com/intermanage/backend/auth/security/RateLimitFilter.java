package com.intermanage.backend.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Odrzuca żądanie API ponad limit adresu ({@link RequestLimits}) z 429 i {@code Retry-After}, zanim filtr JWT
 * sięgnie do bazy. Odpowiedź to ProblemDetail, więc front pokazuje zwykły komunikat błędu.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final java.util.regex.Pattern LIVE = java.util.regex.Pattern.compile("^/api/stores/[^/]+/live$");

    private final RequestLimits limits;

    public RateLimitFilter(RequestLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Strumień wiadomości na żywo to jedno długie połączenie, nie seria zapytań.
        return !request.getRequestURI().startsWith("/api/") || LIVE.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Duration wait = limits.acquire(request);
        if (wait.isZero()) {
            chain.doFilter(request, response);
            return;
        }
        reject(response, wait, "Za dużo zapytań. Spróbuj ponownie za " + seconds(wait) + ".");
    }

    static void reject(HttpServletResponse response, Duration wait, String detail) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, wait.toSeconds())));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    /** „45 s” albo „3 min”. */
    public static String seconds(Duration wait) {
        long s = Math.max(1, wait.toSeconds());
        return s < 90 ? s + " s" : (s + 59) / 60 + " min";
    }
}
