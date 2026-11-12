package com.intermanage.backend.auth.security;

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

    public PasswordLogin(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public AppUserPrincipal authenticate(String username, String password) {
        AppUserPrincipal principal;
        try {
            principal = (AppUserPrincipal) authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password))
                    .getPrincipal();
        } catch (AuthenticationException e) {
            // Jeden komunikat dla złego loginu, hasła i zablokowanego konta - nie zdradzamy, które konta istnieją.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy login lub hasło");
        }
        // Sprawdzane dopiero po poprawnym haśle, żeby komunikat nie zdradzał, które konta istnieją.
        if (!principal.hasStoreAccess()) {
            throw new ApiException(HttpStatus.FORBIDDEN, AuthService.NO_STORE_MESSAGE);
        }
        return principal;
    }
}
