package com.intermanage.backend.auth.security;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Uwierzytelnia żądanie na podstawie JWT z ciasteczka {@value JwtService#COOKIE_NAME} albo, w aplikacji mobilnej,
 * z nagłówka {@code Authorization: Bearer} (odnowiony token wraca wtedy w {@value JwtService#REFRESHED_TOKEN_HEADER}). Przy każdym żądaniu wczytuje
 * użytkownika z bazy, żeby zmiany wprowadzone przez właściciela (uprawnienia roli, zmiana roli, blokada konta,
 * reset hasła) działały od razu, bez ponownego logowania. Nieważny lub wygasły token, zablokowane konto albo inna
 * wersja sesji niż w tokenie zostawiają żądanie nieuwierzytelnione (chronione endpointy zwrócą 401).
 * Ważny, ale starszy token jest wymieniany na nowy ({@link JwtService#refreshIfDue}), więc aktywny użytkownik
 * nie zostaje wylogowany w trakcie pracy; razem z nim odnawiają się konta zalogowane w tle ({@link BackgroundAccounts}).
 * Konto otwarte przed godziną blokady po pracy ({@link WorkdayLock}) albo bez żądań dłużej niż blokada po bezczynności
 * ({@link IdleLock}) trafia do tła i wraca dopiero po PIN-ie.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String LOGOUT_PATH = "/api/auth/logout";
    /**
     * Strumień wiadomości na żywo ({@code GET /api/stores/{id}/live}): przeglądarka łączy się sama, także bez ruchu
     * użytkownika, więc ten żądanie nie odnawia tokenu (inaczej blokada po bezczynności i po pracy nigdy by nie nadeszła).
     */
    private static final Pattern LIVE_PATH = Pattern.compile("^/api/stores/[^/]+/live$");
    /** Sklep z adresu: uprawnienia konta to jego ranga w tym sklepie. */
    private static final Pattern STORE_PATH = Pattern.compile("^/api/stores/([0-9a-fA-F-]{36})(?:/.*)?$");

    private final JwtService jwtService;
    private final BackgroundAccounts backgroundAccounts;
    private final WorkdayLock workdayLock;
    private final IdleLock idleLock;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService, BackgroundAccounts backgroundAccounts,
            WorkdayLock workdayLock, IdleLock idleLock) {
        this.jwtService = jwtService;
        this.backgroundAccounts = backgroundAccounts;
        this.workdayLock = workdayLock;
        this.idleLock = idleLock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Aplikacja mobilna wysyła token w nagłówku; z nagłówkiem ciasteczko się nie liczy.
        String bearer = JwtService.bearerToken(request);
        Cookie cookie = WebUtils.getCookie(request, JwtService.COOKIE_NAME);
        String token = bearer != null ? bearer : cookie != null ? cookie.getValue() : null;
        boolean logout = LOGOUT_PATH.equals(request.getRequestURI());
        boolean live = LIVE_PATH.matcher(request.getRequestURI()).matches();
        UUID storeId = storeOf(request.getRequestURI());
        jwtService.decode(token).ifPresent(jwt -> backgroundAccounts.principalOf(jwt).map(found -> found.forStore(storeId))
                .ifPresent(principal -> {
            Instant now = Instant.now();
            if (bearer == null && !logout && (workdayLock.due(jwt, now) || idleLock.due(jwt, now))) {
                if (workdayLock.protects(principal.getId())) {
                    // Po pracy albo po bezczynności: konto idzie do tła, żądanie zostaje bez logowania (401, strona logowania z PIN-em).
                    backgroundAccounts.moveToBackground(request, response, principal, JwtService.rememberMe(jwt));
                    return;
                }
                // Konto bez PIN-u: blokada nic by nie dała, nowy token, żeby nie sprawdzać tego przy każdym żądaniu.
                // Strumień na żywo tokenu nie wymienia (zrobi to najbliższe zwykłe żądanie).
                if (!live) {
                    jwtService.writeTokenCookie(response, principal, JwtService.rememberMe(jwt));
                }
            }
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            contextHolder.setContext(context);
            // Przy wylogowaniu nie odnawiamy, bo nowe ciasteczko przykryłoby to czyszczące.
            if (!logout && !live
                    && jwtService.refreshIfDue(response, jwt, principal, bearer != null) && bearer == null) {
                backgroundAccounts.refresh(request, response, principal.getId());
            }
        }));
        chain.doFilter(request, response);
    }

    static UUID storeOf(String path) {
        Matcher matcher = path == null ? null : STORE_PATH.matcher(path);
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
