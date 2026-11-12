package com.intermanage.backend.auth.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.repository.AppUserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Konta zalogowane w tle w tej przeglądarce (ciasteczko {@value JwtService#ACCOUNTS_COOKIE_NAME}). Każde ma własny
 * token, ważny na tych samych zasadach co token aktywnego konta: blokada, zmiana hasła (wersja sesji) albo odebrany
 * ostatni sklep wylogowują je przy następnym odczycie. Tokeny w tle są odnawiane razem z tokenem aktywnego konta.
 */
@Component
public class BackgroundAccounts {

    /** Najwięcej kont w tle (poza aktywnym), żeby ciasteczko zmieściło się w 4 KB. */
    public static final int MAX = 6;

    public record Account(Jwt token, AppUserPrincipal principal) {
    }

    private final JwtService jwtService;
    private final AppUserRepository users;
    private final TransactionTemplate readOnlyTx;

    public BackgroundAccounts(JwtService jwtService, AppUserRepository users, PlatformTransactionManager transactionManager) {
        this.jwtService = jwtService;
        this.users = users;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /**
     * Konto z ważnego tokenu, wczytane z bazy: włączone, z dostępem do sklepu i z tą samą wersją sesji co w tokenie.
     */
    public Optional<AppUserPrincipal> principalOf(Jwt jwt) {
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

    /** Ważne konta w tle, bez aktywnego ({@code activeUserId}, może być pusty) i bez powtórzeń, w kolejności zapisu. */
    public List<Account> read(HttpServletRequest request, UUID activeUserId) {
        Set<UUID> seen = new HashSet<>();
        if (activeUserId != null) {
            seen.add(activeUserId);
        }
        List<Account> accounts = new ArrayList<>();
        for (String token : JwtService.accountTokens(request)) {
            jwtService.decode(token)
                    .flatMap(jwt -> principalOf(jwt).map(principal -> new Account(jwt, principal)))
                    .filter(account -> seen.add(account.principal().getId()))
                    .ifPresent(accounts::add);
        }
        return accounts;
    }

    public void write(HttpServletResponse response, List<Account> accounts) {
        jwtService.writeAccountsCookie(response, accounts.stream().map(Account::token).toList());
    }

    /** Nowy token dla konta odkładanego w tle (np. aktywnego przy przełączeniu), z tym samym „nie wylogowuj mnie”. */
    public Account fresh(AppUserPrincipal principal, boolean rememberMe) {
        return new Account(jwtService.issue(principal, rememberMe), principal);
    }

    /** Odnawia tokeny kont w tle; woła go filtr, gdy odnawia token aktywnego konta. */
    public void refresh(HttpServletRequest request, HttpServletResponse response, UUID activeUserId) {
        if (JwtService.accountTokens(request).isEmpty()) {
            return;
        }
        write(response, read(request, activeUserId).stream()
                .map(account -> fresh(account.principal(), JwtService.rememberMe(account.token())))
                .toList());
    }
}
