package com.intermanage.backend.auth.security;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.intermanage.backend.auth.repository.AppUserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Uwierzytelnia żądanie na podstawie JWT z ciasteczka {@value JwtService#COOKIE_NAME}. Przy każdym żądaniu wczytuje
 * użytkownika z bazy, żeby zmiany wprowadzone przez właściciela (uprawnienia roli, zmiana roli, blokada konta,
 * reset hasła) działały od razu, bez ponownego logowania. Nieważny lub wygasły token, zablokowane konto albo inna
 * wersja sesji niż w tokenie zostawiają żądanie nieuwierzytelnione (chronione endpointy zwrócą 401).
 * Ważny, ale starszy token jest wymieniany na nowy ({@link JwtService#refreshIfDue}), więc aktywny użytkownik
 * nie zostaje wylogowany w trakcie pracy.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String LOGOUT_PATH = "/api/auth/logout";

    private final JwtService jwtService;
    private final AppUserRepository users;
    private final TransactionTemplate readOnlyTx;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository users, TransactionTemplate readOnlyTx) {
        this.jwtService = jwtService;
        this.users = users;
        this.readOnlyTx = readOnlyTx;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie cookie = WebUtils.getCookie(request, JwtService.COOKIE_NAME);
        if (cookie != null && !cookie.getValue().isBlank()) {
            decode(cookie.getValue()).ifPresent(jwt -> authenticate(jwt).ifPresent(principal -> {
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
                contextHolder.setContext(context);
                // Przy wylogowaniu nie odnawiamy, bo nowe ciasteczko przykryłoby to czyszczące.
                if (!LOGOUT_PATH.equals(request.getRequestURI())) {
                    jwtService.refreshIfDue(response, jwt, principal);
                }
            }));
        }
        chain.doFilter(request, response);
    }

    private Optional<Jwt> decode(String token) {
        try {
            return Optional.of(jwtService.decoder().decode(token));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    private Optional<AppUserPrincipal> authenticate(Jwt jwt) {
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        Number tokenVersion = jwt.getClaim(JwtService.SESSION_VERSION_CLAIM);
        Optional<AppUserPrincipal> fresh = readOnlyTx.execute(
                status -> users.findWithRoleById(userId).map(AppUserPrincipal::from));

        return fresh
                .filter(AppUserPrincipal::isEnabled)
                // Odebrany ostatni sklep (albo wyłączony sklep) kończy sesję przy następnym żądaniu.
                .filter(AppUserPrincipal::hasStoreAccess)
                .filter(principal -> tokenVersion != null && tokenVersion.intValue() == principal.getSessionVersion())
                .map(principal -> {
                    principal.eraseCredentials();
                    return principal;
                });
    }
}
