package com.intermanage.backend.auth.controller;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.dto.BackgroundChangeResponse;
import com.intermanage.backend.auth.dto.BackgroundVeilRequest;
import com.intermanage.backend.auth.entity.UserBackground;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.service.UserBackgroundService;

import jakarta.validation.Valid;

/** Własne zdjęcie w tle aplikacji zalogowanego konta (Moje konto). */
@RestController
@RequestMapping("/api/auth/background")
public class UserBackgroundController {

    private final UserBackgroundService backgrounds;

    public UserBackgroundController(UserBackgroundService backgrounds) {
        this.backgrounds = backgrounds;
    }

    @PutMapping
    public BackgroundChangeResponse upload(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return backgrounds.upload(principal.getId(), file);
    }

    @PutMapping("/veil")
    public BackgroundChangeResponse veil(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody BackgroundVeilRequest request) {
        return backgrounds.changeVeil(principal.getId(), request.veilPercent());
    }

    @DeleteMapping
    public BackgroundChangeResponse remove(@AuthenticationPrincipal AppUserPrincipal principal) {
        return backgrounds.remove(principal.getId());
    }

    @PostMapping("/{id}/restore")
    public BackgroundChangeResponse restore(@AuthenticationPrincipal AppUserPrincipal principal,
            @PathVariable UUID id) {
        return backgrounds.restore(principal.getId(), id);
    }

    /** Treść zdjęcia pod niezmiennym adresem (nowe zdjęcie = nowe id), więc przeglądarka może je trzymać długo. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        UserBackground background = backgrounds.file(principal.getId(), id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(background.getContentType()))
                .body(background.getContent());
    }
}
