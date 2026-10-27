package com.intermanage.backend.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.dto.ActivateRequest;
import com.intermanage.backend.auth.dto.ChangePasswordRequest;
import com.intermanage.backend.auth.dto.LoginRequest;
import com.intermanage.backend.auth.dto.MeResponse;
import com.intermanage.backend.auth.dto.SetUpPasswordRequest;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.auth.service.AuthService;
import com.intermanage.backend.common.exception.ApiException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Logowanie ustawia ciasteczko HttpOnly z JWT. Wylogowanie ({@code POST /api/auth/logout}, czyści ciasteczko)
 * obsługuje Spring Security, patrz {@link com.intermanage.backend.auth.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthService authService, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /** Ustawia ciasteczko XSRF-TOKEN; frontend woła to przed pierwszym żądaniem modyfikującym. */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken token) {
        token.getToken();
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AppUserPrincipal principal;
        try {
            principal = (AppUserPrincipal) authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()))
                    .getPrincipal();
        } catch (AuthenticationException e) {
            // Jeden komunikat dla złego loginu, hasła i zablokowanego konta - nie zdradzamy, które konta istnieją.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy login lub hasło");
        }
        // Sprawdzane dopiero po poprawnym haśle, żeby komunikat nie zdradzał, które konta istnieją.
        if (!principal.hasStoreAccess()) {
            throw new ApiException(HttpStatus.FORBIDDEN, AuthService.NO_STORE_MESSAGE);
        }
        jwtService.writeTokenCookie(response, principal);
        return authService.me(principal.getId());
    }

    /** Link z maila: zużywa token i loguje pracownika, który musi od razu ustawić hasło. */
    @PostMapping("/activate")
    public MeResponse activate(@Valid @RequestBody ActivateRequest request, HttpServletResponse response) {
        AppUserPrincipal principal = authService.activate(request.token());
        jwtService.writeTokenCookie(response, principal);
        return authService.me(principal.getId());
    }

    @PostMapping("/password/setup")
    public MeResponse setUpPassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SetUpPasswordRequest request, HttpServletResponse response) {
        AppUserPrincipal updated = authService.setUpPassword(principal.getId(), request.newPassword());
        jwtService.writeTokenCookie(response, updated);
        return authService.me(updated.getId());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return authService.me(principal.getId());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request, HttpServletResponse response) {
        AppUserPrincipal updated = authService.changeOwnPassword(
                principal.getId(), request.currentPassword(), request.newPassword());

        // Tokeny z innych urządzeń wygasną (nowa wersja sesji), bieżące dostaje nowy token.
        jwtService.writeTokenCookie(response, updated);
    }
}
