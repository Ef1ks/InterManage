package com.intermanage.backend.auth.security;

import java.io.IOException;

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
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final JwtService jwtService;
    private final BackgroundAccounts backgroundAccounts;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService, BackgroundAccounts backgroundAccounts) {
        this.jwtService = jwtService;
        this.backgroundAccounts = backgroundAccounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Aplikacja mobilna wysyła token w nagłówku; z nagłówkiem ciasteczko się nie liczy.
        String bearer = JwtService.bearerToken(request);
        Cookie cookie = WebUtils.getCookie(request, JwtService.COOKIE_NAME);
        String token = bearer != null ? bearer : cookie != null ? cookie.getValue() : null;
        jwtService.decode(token).ifPresent(jwt -> backgroundAccounts.principalOf(jwt).ifPresent(principal -> {
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities()));
            contextHolder.setContext(context);
            // Przy wylogowaniu nie odnawiamy, bo nowe ciasteczko przykryłoby to czyszczące.
            if (!LOGOUT_PATH.equals(request.getRequestURI())
                    && jwtService.refreshIfDue(response, jwt, principal, bearer != null) && bearer == null) {
                backgroundAccounts.refresh(request, response, principal.getId());
            }
        }));
        chain.doFilter(request, response);
    }
}
