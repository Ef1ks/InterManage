package com.intermanage.backend.push.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.push.service.PushService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Token powiadomień push urządzenia zalogowanego konta (aplikacja mobilna). Bez uprawnień: każdy tylko swoje. */
@RestController
@RequestMapping("/api/me/push-token")
public class PushTokenController {

    public record TokenRequest(@NotBlank @Size(max = 300) String token, String platform) {
    }

    private final PushService push;

    public PushTokenController(PushService push) {
        this.push = push;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody TokenRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        push.register(principal.getId(), request.token(), request.platform());
    }

    /** Wylogowanie na urządzeniu (POST, bo DELETE z treścią bywa gubione przez pośredników). */
    @PostMapping("/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@Valid @RequestBody TokenRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        push.unregister(principal.getId(), request.token());
    }
}
