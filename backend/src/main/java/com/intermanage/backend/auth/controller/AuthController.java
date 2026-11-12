package com.intermanage.backend.auth.controller;

import org.springframework.http.HttpStatus;
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
import com.intermanage.backend.auth.dto.MobileLoginRequest;
import com.intermanage.backend.auth.dto.MobileLoginResponse;
import com.intermanage.backend.auth.dto.SetUpPasswordRequest;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.auth.security.PasswordLogin;
import com.intermanage.backend.auth.service.AccountSwitchService;
import com.intermanage.backend.auth.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
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
    private final PasswordLogin passwordLogin;
    private final JwtService jwtService;
    private final AccountSwitchService accountSwitch;

    public AuthController(AuthService authService, PasswordLogin passwordLogin, JwtService jwtService,
            AccountSwitchService accountSwitch) {
        this.authService = authService;
        this.passwordLogin = passwordLogin;
        this.jwtService = jwtService;
        this.accountSwitch = accountSwitch;
    }

    /** Ustawia ciasteczko XSRF-TOKEN; frontend woła to przed pierwszym żądaniem modyfikującym. */
    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken token) {
        token.getToken();
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AppUserPrincipal principal = passwordLogin.authenticate(request.username(), request.password());
        jwtService.writeTokenCookie(response, principal, Boolean.TRUE.equals(request.rememberMe()));
        // Konto zalogowane w tle przestaje nim być, gdy loguje się na nie hasłem.
        accountSwitch.dropFromBackground(httpRequest, response, principal.getId());
        return authService.me(principal.getId());
    }

    /**
     * Logowanie z aplikacji mobilnej: token wraca w treści (z „nie wylogowuj mnie”), a aplikacja wysyła go w
     * {@code Authorization: Bearer}. Bez ciasteczka i bez CSRF.
     */
    @PostMapping("/mobile/login")
    public MobileLoginResponse mobileLogin(@Valid @RequestBody MobileLoginRequest request) {
        AppUserPrincipal principal = passwordLogin.authenticate(request.username(), request.password());
        return new MobileLoginResponse(jwtService.issueToken(principal, true), authService.me(principal.getId()));
    }

    /** Link z maila: zużywa token i loguje pracownika, który musi od razu ustawić hasło. */
    @PostMapping("/activate")
    public MeResponse activate(@Valid @RequestBody ActivateRequest request, HttpServletResponse response) {
        AppUserPrincipal principal = authService.activate(request.token());
        jwtService.writeTokenCookie(response, principal, false);
        return authService.me(principal.getId());
    }

    @PostMapping("/password/setup")
    public MeResponse setUpPassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SetUpPasswordRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AppUserPrincipal updated = authService.setUpPassword(principal.getId(), request.newPassword());
        jwtService.writeTokenCookie(response, updated, jwtService.rememberMe(httpRequest));
        return authService.me(updated.getId());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return authService.me(principal.getId());
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AppUserPrincipal updated = authService.changeOwnPassword(
                principal.getId(), request.currentPassword(), request.newPassword());

        // Tokeny z innych urządzeń wygasną (nowa wersja sesji), bieżące dostaje nowy token.
        jwtService.writeTokenCookie(response, updated, jwtService.rememberMe(httpRequest));
    }
}
