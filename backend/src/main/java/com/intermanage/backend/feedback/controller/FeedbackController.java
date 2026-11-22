package com.intermanage.backend.feedback.controller;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.StoreAccess;
import com.intermanage.backend.feedback.dto.FeedbackCounts;
import com.intermanage.backend.feedback.dto.FeedbackResponse;
import com.intermanage.backend.feedback.dto.FeedbackStatusRequest;
import com.intermanage.backend.feedback.entity.FeedbackKind;
import com.intermanage.backend.feedback.entity.FeedbackStatus;
import com.intermanage.backend.feedback.service.FeedbackService;

import jakarta.validation.Valid;

/**
 * Zgłoszenia poprawek: {@code /api/feedback} dla każdego zalogowanego (nowe zgłoszenie i własne), a
 * {@code /api/settings/feedback} tylko dla właściciela ({@code SecurityConfig}).
 */
@RestController
public class FeedbackController {

    private final FeedbackService service;
    private final StoreAccess storeAccess;

    public FeedbackController(FeedbackService service, StoreAccess storeAccess) {
        this.service = service;
        this.storeAccess = storeAccess;
    }

    /** Multipart: {@code kind}, {@code description}, {@code pageUrl}, {@code pageLabel}, {@code storeId}, {@code files}. */
    @PostMapping("/api/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse create(@RequestParam(required = false) FeedbackKind kind,
            @RequestParam(required = false) String description, @RequestParam(required = false) String pageUrl,
            @RequestParam(required = false) String pageLabel, @RequestParam(required = false) UUID storeId,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent,
            Authentication authentication, @AuthenticationPrincipal AppUserPrincipal principal) {
        UUID store = storeId != null && storeAccess.canAccess(authentication, storeId) ? storeId : null;
        return service.create(store, kind, description, pageUrl, pageLabel, userAgent, files, principal.getId());
    }

    @GetMapping("/api/feedback/mine")
    public List<FeedbackResponse> mine(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.mine(principal.getId());
    }

    @GetMapping("/api/settings/feedback")
    public List<FeedbackResponse> list(@RequestParam(required = false) FeedbackStatus status) {
        return service.list(status);
    }

    @GetMapping("/api/settings/feedback/counts")
    public FeedbackCounts counts() {
        return service.counts();
    }

    @PutMapping("/api/settings/feedback/{id}/status")
    public FeedbackResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody FeedbackStatusRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.changeStatus(id, request.status(), request.note(), principal.getId());
    }

    @DeleteMapping("/api/settings/feedback/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        service.delete(id, principal.getId());
    }

    @PostMapping("/api/settings/feedback/{id}/restore")
    public FeedbackResponse restore(@PathVariable UUID id) {
        return service.restore(id);
    }

    /** Treść zrzutu; id się nie zmienia, więc przeglądarka może trzymać go długo. */
    @GetMapping("/api/settings/feedback/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        FeedbackService.StoredFile file = service.attachment(id, attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
