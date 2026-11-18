package com.intermanage.backend.importguide.controller;

import java.nio.file.Path;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.ChunkedUploads;
import com.intermanage.backend.importguide.dto.ImportUploadRequest;
import com.intermanage.backend.importguide.dto.ImportUploadResponse;

/**
 * Wysyłka raportu w kawałkach (wolne łącze sklepu: jedno żądanie z całym plikiem przekracza limit Cloudflare).
 * Kolejność: {@code POST /uploads}, {@code PUT /uploads/{id}/chunks/{index}} po kolei, a na końcu import raportu
 * {@code POST <ścieżka raportu>/uploads/{id}}, który sprawdza uprawnienie do tego raportu.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/uploads")
public class ImportUploadController {

    /** Raporty wgrywa Import danych (IMPORTS) albo sprzedaż (cadencier, incydenty). */
    private static final String UPLOAD = "hasAnyAuthority('IMPORTS:EDIT', 'SALES:EDIT')"
            + " and @storeAccess.canAccess(authentication, #storeId)";

    private final ChunkedUploads uploads;

    public ImportUploadController(ChunkedUploads uploads) {
        this.uploads = uploads;
    }

    @PostMapping
    @PreAuthorize(UPLOAD)
    public ImportUploadResponse start(@PathVariable UUID storeId, @Valid @RequestBody ImportUploadRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        String fileName = Path.of(request.fileName()).getFileName().toString();
        UUID id = uploads.start(ChunkedUploads.owner(storeId, principal.getId()), fileName, request.size());
        return new ImportUploadResponse(id, ChunkedUploads.CHUNK_SIZE);
    }

    @PutMapping(path = "/{uploadId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(UPLOAD)
    public void chunk(@PathVariable UUID storeId, @PathVariable UUID uploadId, @PathVariable int index,
            @RequestBody byte[] bytes, @AuthenticationPrincipal AppUserPrincipal principal) {
        uploads.append(ChunkedUploads.owner(storeId, principal.getId()), uploadId, index, bytes);
    }
}
