package com.intermanage.backend.download.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.download.dto.ShortcutRequest;
import com.intermanage.backend.download.dto.ShortcutResponse;
import com.intermanage.backend.download.service.DownloadShortcutService;

/** Skróty do folderów: zmienia tylko właściciel (jak całe {@code /api/settings/**}). Każda zmiana zwraca listę. */
@RestController
@RequestMapping("/api/settings/downloads/shortcuts")
public class ShortcutSettingsController {

    private final DownloadShortcutService shortcuts;

    public ShortcutSettingsController(DownloadShortcutService shortcuts) {
        this.shortcuts = shortcuts;
    }

    /** Multipart {@code name} i {@code file} (skrót .lnk). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ShortcutResponse> create(@RequestParam(required = false) String name,
            @RequestParam(required = false) MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        shortcuts.create(name, file, principal.getId());
        return shortcuts.list();
    }

    @PutMapping("/{id}")
    public List<ShortcutResponse> rename(@PathVariable UUID id, @RequestBody ShortcutRequest request) {
        shortcuts.rename(id, request.name());
        return shortcuts.list();
    }

    /** Znacznik; „Przywróć” to {@code POST …/{id}/restore}. */
    @DeleteMapping("/{id}")
    public List<ShortcutResponse> remove(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        shortcuts.remove(id, principal.getId());
        return shortcuts.list();
    }

    @PostMapping("/{id}/restore")
    public List<ShortcutResponse> restore(@PathVariable UUID id) {
        shortcuts.restore(id);
        return shortcuts.list();
    }
}
