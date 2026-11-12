package com.intermanage.backend.auth.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.dto.LoginRequest;
import com.intermanage.backend.auth.dto.MeResponse;
import com.intermanage.backend.auth.dto.PinRequiredRequest;
import com.intermanage.backend.auth.dto.PinSettingsResponse;
import com.intermanage.backend.auth.dto.SetPinRequest;
import com.intermanage.backend.auth.dto.SwitchAccountRequest;
import com.intermanage.backend.auth.dto.SwitchAccountResponse;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.PasswordLogin;
import com.intermanage.backend.auth.service.AccountSwitchService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Przełączanie kont w przeglądarce ({@link AccountSwitchService}). Lista kont w tle, przejście na konto, wylogowanie
 * konta w tle i wszystkich kont działają bez aktywnego konta (strona logowania), patrz {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/auth")
public class AccountSwitchController {

    private final AccountSwitchService accountSwitch;
    private final PasswordLogin passwordLogin;

    public AccountSwitchController(AccountSwitchService accountSwitch, PasswordLogin passwordLogin) {
        this.accountSwitch = accountSwitch;
        this.passwordLogin = passwordLogin;
    }

    @GetMapping("/accounts")
    public List<SwitchAccountResponse> accounts(@AuthenticationPrincipal AppUserPrincipal principal,
            HttpServletRequest request, HttpServletResponse response) {
        return accountSwitch.list(request, response, principal);
    }

    @PostMapping("/accounts")
    public MeResponse add(@AuthenticationPrincipal AppUserPrincipal principal, @Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        return accountSwitch.add(request, response, principal,
                passwordLogin.authenticate(body.username(), body.password()), Boolean.TRUE.equals(body.rememberMe()));
    }

    @PostMapping("/accounts/{userId}/switch")
    public MeResponse switchTo(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID userId,
            @RequestBody(required = false) SwitchAccountRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return accountSwitch.switchTo(request, response, principal, userId, body);
    }

    @DeleteMapping("/accounts/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID userId,
            HttpServletRequest request, HttpServletResponse response) {
        accountSwitch.remove(request, response, principal, userId);
    }

    @PostMapping("/accounts/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(HttpServletResponse response) {
        accountSwitch.logoutAll(response);
    }

    @GetMapping("/pin")
    public PinSettingsResponse pin(@AuthenticationPrincipal AppUserPrincipal principal) {
        return accountSwitch.pinSettings(principal.getId());
    }

    @PutMapping("/pin")
    public PinSettingsResponse setPin(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody SetPinRequest body) {
        return accountSwitch.setPin(principal.getId(), body.currentPassword(), body.pin());
    }

    @PutMapping("/pin/required")
    public PinSettingsResponse setPinRequired(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody PinRequiredRequest body) {
        return accountSwitch.setPinRequired(principal.getId(), body.required());
    }
}
