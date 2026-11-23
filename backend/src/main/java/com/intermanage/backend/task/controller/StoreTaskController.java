package com.intermanage.backend.task.controller;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.task.dto.TaskAssigneeResponse;
import com.intermanage.backend.task.dto.TaskCounts;
import com.intermanage.backend.task.dto.TaskEventResponse;
import com.intermanage.backend.task.dto.TaskRequest;
import com.intermanage.backend.task.dto.TaskResponse;
import com.intermanage.backend.task.dto.TaskSmallRequests;
import com.intermanage.backend.task.service.StoreTaskService;

import jakarta.validation.Valid;

/**
 * Zadania sklepu. Swoje zadania widzi i odhacza każdy z dostępem do sklepu (bez uprawnień, jak „Mój grafik”);
 * zlecanie, zmiany, przyjmowanie i usuwanie: właściciel (wszystkie zadania) i konta z włączonym zlecaniem (zadania,
 * które same zleciły).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/tasks")
public class StoreTaskController {

    /** Zlecanie: właściciel albo konto, któremu włączył zlecanie; czy to jego zadanie, sprawdza serwis. */
    private static final String OWNER = "hasAuthority('TASKS:ASSIGN') and " + Access.STORE;

    private final StoreTaskService service;

    public StoreTaskController(StoreTaskService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    @PreAuthorize(Access.STORE)
    public List<TaskResponse> mine(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.mine(storeId, principal.getId());
    }

    @GetMapping
    @PreAuthorize(OWNER)
    public List<TaskResponse> all(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.all(storeId, principal.getId());
    }

    @GetMapping("/counts")
    @PreAuthorize(Access.STORE)
    public TaskCounts counts(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.counts(storeId, principal.getId());
    }

    @GetMapping("/assignees")
    @PreAuthorize(OWNER)
    public List<TaskAssigneeResponse> assignees(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.assignees(storeId, principal.getId());
    }

    /** Czy zalogowany zleca całemu sklepowi (właściciel, Aderon, włączone zlecanie): {@code {"allowed": true}}. */
    @GetMapping("/store-assignable")
    @PreAuthorize(OWNER)
    public java.util.Map<String, Boolean> storeAssignable(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return java.util.Map.of("allowed", service.canAssignToStore(storeId, principal.getId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Access.STORE)
    public TaskResponse get(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.get(storeId, id, principal.getId());
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(Access.STORE)
    public List<TaskEventResponse> history(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.history(storeId, id, principal.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(OWNER)
    public TaskResponse create(@PathVariable UUID storeId, @Valid @RequestBody TaskRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(OWNER)
    public TaskResponse update(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody TaskRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.update(storeId, id, request, principal.getId());
    }

    @PostMapping("/{id}/items/{itemId}")
    @PreAuthorize(Access.STORE)
    public TaskResponse checkItem(@PathVariable UUID storeId, @PathVariable UUID id, @PathVariable UUID itemId,
            @Valid @RequestBody TaskSmallRequests.ItemCheck request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.checkItem(storeId, id, itemId, request.checked(), request.note(), principal.getId());
    }

    @PostMapping("/{id}/done")
    @PreAuthorize(Access.STORE)
    public TaskResponse done(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody(required = false) TaskSmallRequests.Comment request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.done(storeId, id, request == null ? null : request.comment(), principal.getId());
    }

    /** „Wszyscy już zrobili”: zamyka otwarte kopie zlecenia kilku osobom (wykonawca z grupy albo zlecający). */
    @PostMapping("/{id}/done-all")
    @PreAuthorize(Access.STORE)
    public TaskResponse doneAll(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody(required = false) TaskSmallRequests.Comment request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.doneAll(storeId, id, request == null ? null : request.comment(), principal.getId());
    }

    @PostMapping("/{id}/undone")
    @PreAuthorize(Access.STORE)
    public TaskResponse undone(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.undone(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize(OWNER)
    public TaskResponse accept(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.accept(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/return")
    @PreAuthorize(OWNER)
    public TaskResponse sendBack(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody(required = false) TaskSmallRequests.Comment request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.sendBack(storeId, id, request == null ? null : request.comment(), principal.getId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(OWNER)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(OWNER)
    public TaskResponse restore(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restore(storeId, id, principal.getId());
    }

    /** Zrzut ekranu albo zdjęcie (multipart {@code file}); dodaje wykonawca albo zlecający. */
    @PostMapping("/{id}/attachments")
    @PreAuthorize(Access.STORE)
    public TaskResponse addAttachment(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.addAttachment(storeId, id, file, principal.getId());
    }

    /** Treść załącznika; id się nie zmienia, więc przeglądarka może trzymać go długo. */
    @GetMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize(Access.STORE)
    public ResponseEntity<byte[]> attachment(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID attachmentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        StoreTaskService.StoredFile file = service.attachment(storeId, id, attachmentId, principal.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize(Access.STORE)
    public TaskResponse removeAttachment(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID attachmentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.removeAttachment(storeId, id, attachmentId, principal.getId());
    }

    @PostMapping("/{id}/attachments/{attachmentId}/restore")
    @PreAuthorize(Access.STORE)
    public TaskResponse restoreAttachment(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID attachmentId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restoreAttachment(storeId, id, attachmentId, principal.getId());
    }
}
