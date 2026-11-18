package com.intermanage.backend.auth.security;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import com.intermanage.backend.auth.service.AuthService;
import com.intermanage.backend.common.exception.ApiException;

/** Sprawdza login i hasło (logowanie w przeglądarce, w aplikacji mobilnej i dodanie konta przy przełączaniu). */
@Component
public class PasswordLogin {

    private final AuthenticationManager authenticationManager;
    private final RequestLimits limits;

    public PasswordLogin(AuthenticationManager authenticationManager, RequestLimits limits) {
        this.authenticationManager = authenticationManager;
        this.limits = limits;
    }

    public AppUserPrincipal authenticate(String username, String password) {
        // Zgadywanie hasła jednego loginu z wielu adresów: po serii błędów login czeka, także z dobrym hasłem.
        Duration blocked = limits.loginBlocked(username);
        if (!blocked.isZero()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Za dużo nieudanych logowań na to konto. "
                    + "Spróbuj ponownie za " + RateLimitFilter.seconds(blocked) + ".");
        }
        AppUserPrincipal principal;
        try {
            principal = (AppUserPrincipal) authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password))
                    .getPrincipal();
        } catch (AuthenticationException e) {
            limits.loginFailed(username);
            // Jeden komunikat dla złego loginu, hasła i zablokowanego konta - nie zdradzamy, które konta istnieją.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy login lub hasło");
        }
        limits.loginSucceeded(username);
        // Sprawdzane dopiero po poprawnym haśle, żeby komunikat nie zdradzał, które konta istnieją.
        if (!principal.hasStoreAccess()) {
            throw new ApiException(HttpStatus.FORBIDDEN, AuthService.NO_STORE_MESSAGE);
        }
        return principal;
    }
}
