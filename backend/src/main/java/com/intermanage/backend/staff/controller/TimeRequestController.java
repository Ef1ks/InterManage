package com.intermanage.backend.staff.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.dto.TimeRequestInput;
import com.intermanage.backend.staff.dto.TimeRequestResponse;
import com.intermanage.backend.staff.service.StaffTimeRequestService;
import com.intermanage.backend.staff.service.StaffTimeService;

import jakarta.validation.Valid;

/**
 * Wnioski pracowników w sklepie: kierownik działu ({@code STAFF_TIME:EDIT}) decyduje o wnioskach swoich działów, kadry
 * ({@code STAFF_RECORDS:EDIT}) zatwierdzają po kierowniku. Kto o którym wniosku decyduje, sprawdza serwis.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/staff/time/requests")
public class TimeRequestController {

    private static final String VIEW = "(hasAuthority('STAFF_TIME:VIEW') or hasAuthority('STAFF_RECORDS:VIEW'))"
            + " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT = "(hasAuthority('STAFF_TIME:EDIT') or hasAuthority('STAFF_RECORDS:EDIT'))"
            + " and @storeAccess.canAccess(authentication, #storeId)";
    private static final int MAX_DAYS = 92;

    private final StaffTimeRequestService requests;

    public TimeRequestController(StaffTimeRequestService requests) {
        this.requests = requests;
    }

    /** Bez {@code from} i {@code to}: otwarte (do decyzji); z nimi: wszystkie z dni okresu (do 92 dni). */
    @GetMapping
    @PreAuthorize(VIEW)
    public List<TimeRequestResponse> list(@PathVariable UUID storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        if ((from == null) != (to == null)) {
            throw ApiException.badRequest("Podaj oba końce okresu");
        }
        if (from != null && (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to))) {
            throw ApiException.badRequest("Okres od 1 do " + MAX_DAYS + " dni");
        }
        return requests.list(storeId, from == null, from, to, principal);
    }

    @GetMapping("/count")
    @PreAuthorize(VIEW)
    public Map<String, Integer> count(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.count(storeId, principal);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize(EDIT)
    public TimeRequestResponse approve(@PathVariable UUID storeId, @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) TimeRequestInput.Step step,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.approve(storeId, requestId, step, principal);
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize(EDIT)
    public TimeRequestResponse reject(@PathVariable UUID storeId, @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) TimeRequestInput.Step step,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.reject(storeId, requestId, step, principal);
    }

    @PostMapping("/{requestId}/undo")
    @PreAuthorize(EDIT)
    public TimeRequestResponse undo(@PathVariable UUID storeId, @PathVariable UUID requestId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.undo(storeId, requestId, principal);
    }

    /** Wydruk wniosków ({@code ids} powtarzane), każdy na osobnej kartce. */
    @GetMapping("/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @RequestParam List<UUID> ids,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        StaffTimeService.PdfFile file = requests.pdf(storeId, ids, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }
}
