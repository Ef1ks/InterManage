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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.dto.AvatarChangeResponse;
import com.intermanage.backend.auth.entity.UserAvatar;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.service.UserAvatarService;

/** Zdjęcie profilowe zalogowanego konta (Moje konto). */
@RestController
@RequestMapping("/api/auth/avatar")
public class UserAvatarController {

    private final UserAvatarService avatars;

    public UserAvatarController(UserAvatarService avatars) {
        this.avatars = avatars;
    }

    @PutMapping
    public AvatarChangeResponse upload(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return avatars.upload(principal.getId(), file);
    }

    @DeleteMapping
    public AvatarChangeResponse remove(@AuthenticationPrincipal AppUserPrincipal principal) {
        return avatars.remove(principal.getId());
    }

    @PostMapping("/{id}/restore")
    public AvatarChangeResponse restore(@AuthenticationPrincipal AppUserPrincipal principal, @PathVariable UUID id) {
        return avatars.restore(principal.getId(), id);
    }

    /** Zdjęcie zapisane w bazie (bez R2) pod niezmiennym adresem, więc przeglądarka może je trzymać długo. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        UserAvatar avatar = avatars.file(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(avatar.getContentType()))
                .body(avatar.getContent());
    }
}
