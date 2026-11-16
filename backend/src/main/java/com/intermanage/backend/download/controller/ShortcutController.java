package com.intermanage.backend.download.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.download.dto.ShortcutResponse;
import com.intermanage.backend.download.entity.DownloadShortcut;
import com.intermanage.backend.download.service.DownloadShortcutService;

/** Skróty do folderów dla każdego zalogowanego; zmiany są pod {@code /api/settings/downloads/shortcuts}. */
@RestController
@RequestMapping("/api/downloads/shortcuts")
public class ShortcutController {

    private final DownloadShortcutService shortcuts;

    public ShortcutController(DownloadShortcutService shortcuts) {
        this.shortcuts = shortcuts;
    }

    @GetMapping
    public List<ShortcutResponse> list() {
        return shortcuts.list();
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        DownloadShortcut shortcut = shortcuts.file(id);
        return attachment(shortcut.getFileName(), shortcut.getContent());
    }

    /** Wszystkie skróty w jednym ZIP-ie. */
    @GetMapping("/zip")
    public ResponseEntity<byte[]> zip() {
        return attachment("Skróty do folderów.zip", shortcuts.zip());
    }

    private static ResponseEntity<byte[]> attachment(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }
}
