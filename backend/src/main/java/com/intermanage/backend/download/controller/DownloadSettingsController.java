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
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.download.dto.DownloadRequest;
import com.intermanage.backend.download.dto.DownloadResponse;
import com.intermanage.backend.download.service.DownloadService;

import jakarta.validation.Valid;

/** Zarządzanie stroną „Do pobrania” (tylko właściciel, jak całe {@code /api/settings/**}). Każda zmiana zwraca listę. */
@RestController
@RequestMapping("/api/settings/downloads")
public class DownloadSettingsController {

    private final DownloadService downloads;

    public DownloadSettingsController(DownloadService downloads) {
        this.downloads = downloads;
    }

    /** Nowa pozycja: multipart {@code title}, {@code description}, opcjonalnie {@code file} i {@code version}. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DownloadResponse> create(@RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) MultipartFile file, @RequestParam(required = false) String version,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        downloads.create(valid(title, description), file, version, principal.getId());
        return downloads.list(true);
    }

    @PutMapping("/{id}")
    public List<DownloadResponse> update(@PathVariable UUID id, @Valid @RequestBody DownloadRequest request) {
        downloads.update(id, request);
        return downloads.list(true);
    }

    /** Nowa wersja pliku (multipart {@code file}, opcjonalnie {@code version}). */
    @PutMapping(path = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<DownloadResponse> replaceFile(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String version, @AuthenticationPrincipal AppUserPrincipal principal) {
        downloads.replaceFile(id, file, version, principal.getId());
        return downloads.list(true);
    }

    /** Usuwa pozycję (znacznik); „Przywróć” to {@code POST …/{id}/restore}. */
    @DeleteMapping("/{id}")
    public List<DownloadResponse> remove(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        downloads.remove(id, principal.getId());
        return downloads.list(true);
    }

    @PostMapping("/{id}/restore")
    public List<DownloadResponse> restore(@PathVariable UUID id) {
        downloads.restore(id);
        return downloads.list(true);
    }

    private static DownloadRequest valid(String title, String description) {
        if (title == null || title.isBlank()) {
            throw ApiException.badRequest("Podaj nazwę");
        }
        if (title.strip().length() > 120) {
            throw ApiException
                    .badRequest("Nazwa może mieć najwyżej 120 znaków");
        }
        if (description != null && description.length() > 2000) {
            throw ApiException
                    .badRequest("Opis może mieć najwyżej 2000 znaków");
        }
        return new DownloadRequest(title, description);
    }
}
