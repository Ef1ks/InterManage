package com.intermanage.backend.agent.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.WebUtils;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.BackgroundAccounts;
import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.auth.security.StoreAccess;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.exception.ApiException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Wiadomości na żywo dla przeglądarek sklepu (Server-Sent Events): zapis plików przez agenta, wczytane zmiany cen,
 * pliki cen z kasy, dziennik ATHOS, stan agenta. Połączenie nie odnawia tokenu i nie liczy się jako aktywność
 * ({@code JwtAuthenticationFilter} pomija tę ścieżkę), więc blokada po bezczynności i po pracy działają jak dotąd;
 * strumień kończy się z ważnością tokenu, a co 20 s sprawdzamy, czy konto dalej ma dostęp do sklepu.
 */
@RestController
public class LiveController {

    /** Ścieżka strumienia (filtry JWT, limitów i dziennika sklepu ją rozpoznają). */
    public static final String PATH_PATTERN = "^/api/stores/[^/]+/live$";

    private final LiveEvents live;
    private final JwtService jwtService;
    private final BackgroundAccounts accounts;
    private final StoreAccess storeAccess;

    public LiveController(LiveEvents live, JwtService jwtService, BackgroundAccounts accounts,
            StoreAccess storeAccess) {
        this.live = live;
        this.jwtService = jwtService;
        this.accounts = accounts;
        this.storeAccess = storeAccess;
    }

    public static boolean isLivePath(String path) {
        return path != null && path.matches(PATH_PATTERN);
    }

    @GetMapping(value = "/api/stores/{storeId}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(Access.STORE)
    public SseEmitter live(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        String bearer = JwtService.bearerToken(request);
        Cookie cookie = WebUtils.getCookie(request, JwtService.COOKIE_NAME);
        String token = bearer != null ? bearer : cookie != null ? cookie.getValue() : null;
        Jwt jwt = jwtService.decode(token).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Zaloguj się ponownie"));
        Instant expiresAt = jwt.getExpiresAt() != null ? jwt.getExpiresAt() : Instant.now().plusSeconds(3600);
        // nginx nie buforuje strumienia (dla pewności także nagłówkiem, gdyby konfiguracja go pominęła).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        UUID userId = principal.getId();
        return live.subscribe(storeId, userId, expiresAt, () -> jwtService.decode(token)
                .flatMap(accounts::principalOf)
                .filter(fresh -> fresh.getId().equals(userId))
                .map(fresh -> storeAccess.canAccess(UsernamePasswordAuthenticationToken.authenticated(fresh, null,
                        fresh.getAuthorities()), storeId))
                .orElse(false));
    }
}
