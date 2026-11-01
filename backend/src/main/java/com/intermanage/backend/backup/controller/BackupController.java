package com.intermanage.backend.backup.controller;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.backup.dto.BackupSettingsRequest;
import com.intermanage.backend.backup.dto.BackupSettingsResponse;
import com.intermanage.backend.backup.dto.GoogleAuthorizeResponse;
import com.intermanage.backend.backup.service.BackupRunner;
import com.intermanage.backend.backup.service.BackupService;

import jakarta.validation.Valid;

/** Kopia zapasowa bazy na Dysk Google. Tylko dla właściciela - reguła dla /api/settings/** w SecurityConfig. */
@RestController
@RequestMapping("/api/settings/backup")
public class BackupController {

    private final BackupService service;
    private final BackupRunner runner;

    public BackupController(BackupService service, BackupRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @GetMapping
    public BackupSettingsResponse get() {
        return service.get(runner.isRunning());
    }

    @PutMapping
    public BackupSettingsResponse save(@Valid @RequestBody BackupSettingsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.save(request, principal.getId());
        return get();
    }

    /** Adres okna zgody Google; front przekierowuje na niego całą stronę. */
    @PostMapping("/google/authorize")
    public GoogleAuthorizeResponse authorize() {
        return service.authorize();
    }

    /** Powrót z Google (nawigacja przeglądarki, ciasteczko SameSite=Lax idzie): przekierowanie do ustawień. */
    @GetMapping("/google/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        String location = service.callback(code, state, error, principal.getId());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    @DeleteMapping("/google")
    public BackupSettingsResponse disconnect(@AuthenticationPrincipal AppUserPrincipal principal) {
        service.disconnect(principal.getId());
        return get();
    }

    /** „Zrób kopię teraz”: kopia idzie w tle, front odpytuje {@code GET} do końca. */
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BackupSettingsResponse run(@AuthenticationPrincipal AppUserPrincipal principal) {
        runner.startManual(principal.getId());
        return get();
    }
}
