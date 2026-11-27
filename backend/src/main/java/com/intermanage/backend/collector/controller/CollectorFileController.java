package com.intermanage.backend.collector.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.collector.dto.CollectorFileDtos;
import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.collector.entity.CollectorFile;
import com.intermanage.backend.collector.service.CollectorFileService;

import jakarta.validation.Valid;

/** Inwentaryzacje i zamówienia z kolektora w historii; uprawnienie jak listy z kolektora. */
@RestController
@RequestMapping("/api/stores/{storeId}/collector-files")
@PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and " + Access.STORE)
public class CollectorFileController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CollectorFileService service;

    public CollectorFileController(CollectorFileService service) {
        this.service = service;
    }

    @GetMapping
    public List<CollectorFileDtos.Response> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Czekające na wgranie (licznik w menu, osobno inwentaryzacje i zamówienia). */
    @GetMapping("/count")
    public Map<String, Long> count(@PathVariable UUID storeId, @RequestParam CollectorFile.Kind kind) {
        return Map.of("waiting", service.countWaiting(storeId, kind));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectorFileDtos.Created create(@PathVariable UUID storeId,
            @Valid @RequestBody CollectorFileDtos.CreateRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @GetMapping("/{id}")
    public CollectorListDetails details(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.details(storeId, id);
    }

    /** Plik dla programu sklepu (inwentaryzacja albo zamówienie); po zapisaniu w folderze {@code /loaded}. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        CollectorFileService.StoredFile file = service.file(storeId, id, principal.getUsername());
        return file(file.content(), file.fileName(), MediaType.parseMediaType("text/plain;charset=US-ASCII"), false);
    }

    @PostMapping("/{id}/loaded")
    public CollectorFileDtos.Response loaded(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody CollectorFileDtos.LoadedRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.loaded(storeId, id, request.fileName(), principal.getId());
    }

    @GetMapping("/{id}/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @PathVariable UUID id) {
        return file(service.xlsx(storeId, id), "Z kolektora.xlsx", XLSX, false);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(service.pdf(storeId, id, principal.getUsername()), "Z kolektora.pdf", MediaType.APPLICATION_PDF,
                true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.remove(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        service.restore(storeId, id);
    }

    private static ResponseEntity<byte[]> file(byte[] content, String name, MediaType type, boolean inline) {
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).contentType(type)
                .body(content);
    }
}
