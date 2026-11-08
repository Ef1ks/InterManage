package com.intermanage.backend.download.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.entity.DownloadFile;
import com.intermanage.backend.download.service.DownloadService;

/** „Do pobrania” dla każdego zalogowanego; zmiany są pod {@code /api/settings/downloads} (właściciel). */
@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final DownloadService downloads;

    public DownloadController(DownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping
    public List<DownloadResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return downloads.list(principal.getAuthorities().stream()
                .anyMatch(authority -> AppUserPrincipal.OWNER_AUTHORITY.equals(authority.getAuthority())));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id) {
        DownloadFile file = downloads.file(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.getContent());
    }
}
