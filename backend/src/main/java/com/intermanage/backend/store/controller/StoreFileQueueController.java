package com.intermanage.backend.store.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.store.dto.FileQueueItemResponse;
import com.intermanage.backend.store.entity.StoreFileQueueItem;
import com.intermanage.backend.store.service.StoreFileQueueService;

/**
 * Kolejka plików sklepu do zapisu w folderach (praca dla innego sklepu bez połączenia z jego komputerem). Dla każdego
 * z dostępem do sklepu: pliki trafiają tu z narzędzi, które same sprawdzają swoje uprawnienia, a zapisuje je ktoś
 * na komputerze sklepu.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/file-queue")
public class StoreFileQueueController {

    private final StoreFileQueueService service;

    public StoreFileQueueController(StoreFileQueueService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Access.STORE)
    public List<FileQueueItemResponse> pending(@PathVariable UUID storeId) {
        return service.pending(storeId);
    }

    @GetMapping("/count")
    @PreAuthorize(Access.STORE)
    public Map<String, Long> count(@PathVariable UUID storeId) {
        return Map.of("count", service.countPending(storeId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Access.STORE)
    public FileQueueItemResponse add(@PathVariable UUID storeId, @RequestParam("folderKey") String folderKey,
            @RequestParam(value = "folderLabel", required = false) String folderLabel,
            @RequestParam(value = "groupKey", required = false) String groupKey,
            @RequestParam(value = "groupLabel", required = false) String groupLabel,
            @RequestParam("fileName") String fileName, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.add(storeId, folderKey, folderLabel, groupKey, groupLabel, fileName, file,
                principal.getId());
    }

    @GetMapping("/{id}/file")
    @PreAuthorize(Access.STORE)
    public ResponseEntity<byte[]> file(@PathVariable UUID storeId, @PathVariable UUID id) {
        StoreFileQueueItem item = service.file(storeId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(item.getFileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(item.getContent());
    }

    /** Plik zapisany w folderze sklepu: znika z kolejki. */
    @PostMapping("/{id}/saved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Access.STORE)
    public void saved(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.markSaved(storeId, id, principal.getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Access.STORE)
    public void remove(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.remove(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Access.STORE)
    public void restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        service.restore(storeId, id);
    }
}
