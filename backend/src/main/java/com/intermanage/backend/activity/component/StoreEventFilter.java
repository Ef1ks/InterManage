package com.intermanage.backend.activity.component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.entity.StoreEventLevel;
import com.intermanage.backend.auth.security.AppUserPrincipal;

/**
 * Zapisuje w dzienniku sklepu każdą zmianę przez API (POST, PUT, PATCH, DELETE): kto, w którym sklepie, co i z jakim
 * wynikiem, także odrzuconą (403, 409, błąd serwera). Odczyty się nie liczą. Treści żądań nie zapisujemy, bo bywają w
 * nich hasła, PIN-y i dane kadrowe; opis daje {@link StoreEventCatalog} ze ścieżki. Na czas żądania kładzie sklep,
 * osobę i moduł do MDC, więc ostrzeżenia z logów trafiają do dziennika z tym kontekstem ({@link StoreEventLogAppender}).
 *
 * <p>Stoi w łańcuchu Spring Security zaraz po filtrze JWT: zna już zalogowaną osobę, a odmowę dostępu z
 * {@code @PreAuthorize} widzi jako status odpowiedzi.
 */
public class StoreEventFilter extends OncePerRequestFilter {

    public static final String MDC_STORE = "storeId";
    public static final String MDC_USER = "userId";
    public static final String MDC_MODULE = "module";

    private static final Pattern STORE_PATH = Pattern.compile(
            "^/api/stores/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");
    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Logowanie, własne konto, czytniki kart i usługi pomocnicze: to nie są zdarzenia sklepu. */
    private static final List<String> SKIPPED_PREFIXES = List.of("/api/auth/", "/api/me/", "/api/time-clock/",
            "/api/gus/");
    /** Kawałki wysyłanych plików i znaczniki odczytu: szum, a nie zmiana. */
    private static final Pattern SKIPPED = Pattern.compile(
            ".*/(chunks/\\d+|imports/uploads|seen|preview|read|pending)$");

    private final StoreEventBuffer buffer;

    public StoreEventFilter(StoreEventBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !buffer.enabled() || !path.startsWith("/api/")
                || SKIPPED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        Matcher store = STORE_PATH.matcher(path);
        UUID storeId = store.matches() ? UUID.fromString(store.group(1)) : null;
        StoreEventCatalog.Description description = storeId != null
                ? StoreEventCatalog.describeStore(method, store.group(2) == null ? "" : store.group(2))
                : StoreEventCatalog.describeCompany(method, path);
        UUID userId = currentUser();

        if (storeId != null) {
            MDC.put(MDC_STORE, storeId.toString());
        }
        if (userId != null) {
            MDC.put(MDC_USER, userId.toString());
        }
        MDC.put(MDC_MODULE, description.module());
        long started = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            MDC.remove(MDC_STORE);
            MDC.remove(MDC_USER);
            MDC.remove(MDC_MODULE);
            if (CHANGES.contains(method) && !SKIPPED.matcher(path).matches()) {
                int duration = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
                boolean failed = status >= 400;
                UUID user = userId != null ? userId : currentUser();
                buffer.record(StoreEventRecord.now(storeId, user,
                        failed ? StoreEventKind.FAILED : StoreEventKind.ACTION,
                        status >= 500 ? StoreEventLevel.ERROR : failed ? StoreEventLevel.WARN : StoreEventLevel.INFO,
                        description.module(), method, path, status, duration, description.summary(), null));
            }
        }
    }

    private static UUID currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal
                ? principal.getId()
                : null;
    }
}
