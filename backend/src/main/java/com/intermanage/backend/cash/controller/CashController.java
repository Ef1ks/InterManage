package com.intermanage.backend.cash.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.cash.dto.CashCancelRequest;
import com.intermanage.backend.cash.dto.CashClosingResponse;
import com.intermanage.backend.cash.dto.CashClosingSummary;
import com.intermanage.backend.cash.dto.CashDocumentRequest;
import com.intermanage.backend.cash.dto.CashDocumentResponse;
import com.intermanage.backend.cash.dto.CashOpeningBalanceRequest;
import com.intermanage.backend.cash.dto.CashReport;
import com.intermanage.backend.cash.dto.CashSettings;
import com.intermanage.backend.cash.service.CashClosingService;
import com.intermanage.backend.cash.service.CashService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Raport kasowy sklepu. VIEW = praca kasjera: raport, historia, wystawianie KP i KW, wydruki. EDIT = anulowanie
 * dokumentu, pieczątka i stan początkowy kasy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/cash")
public class CashController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAuthority('CASH_REPORT:VIEW')" + STORE;
    private static final String EDIT = "hasAuthority('CASH_REPORT:EDIT')" + STORE;

    private final CashService service;
    private final CashClosingService closings;

    public CashController(CashService service, CashClosingService closings) {
        this.service = service;
        this.closings = closings;
    }

    @GetMapping("/report")
    @PreAuthorize(VIEW)
    public CashReport report(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(storeId, from, to);
    }

    @GetMapping("/report/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> reportPdf(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(service.reportPdf(storeId, from, to, principal.getId()));
    }

    /** Zakończone raporty dzienne (o 23:00), od najnowszego. */
    @GetMapping("/closings")
    @PreAuthorize(VIEW)
    public List<CashClosingSummary> closings(@PathVariable UUID storeId) {
        return closings.list(storeId);
    }

    @GetMapping("/closings/{id}")
    @PreAuthorize(VIEW)
    public CashClosingResponse closing(@PathVariable UUID storeId, @PathVariable UUID id) {
        return closings.get(storeId, id);
    }

    @GetMapping("/closings/{id}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> closingPdf(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(closings.pdf(storeId, id, principal.getId()));
    }

    @GetMapping("/documents")
    @PreAuthorize(VIEW)
    public List<CashDocumentResponse> history(@PathVariable UUID storeId) {
        return service.history(storeId);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(VIEW)
    public CashDocumentResponse create(@PathVariable UUID storeId, @Valid @RequestBody CashDocumentRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @GetMapping("/documents/{id}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> documentPdf(@PathVariable UUID storeId, @PathVariable UUID id) {
        return file(service.documentPdf(storeId, id));
    }

    /** Anulowanie (znacznik z powodem); „Przywróć” to {@code POST …/{id}/restore}. */
    @PostMapping("/documents/{id}/cancel")
    @PreAuthorize(EDIT)
    public CashDocumentResponse cancel(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody CashCancelRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.cancel(storeId, id, request.reason(), principal.getId());
    }

    @PostMapping("/documents/{id}/restore")
    @PreAuthorize(EDIT)
    public CashDocumentResponse restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }

    @GetMapping("/settings")
    @PreAuthorize(VIEW)
    public CashSettings settings(@PathVariable UUID storeId) {
        return service.settings(storeId);
    }

    @PutMapping("/stamp")
    @PreAuthorize(EDIT)
    public CashSettings uploadStamp(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.uploadStamp(storeId, file, principal.getId());
    }

    @DeleteMapping("/stamp")
    @PreAuthorize(EDIT)
    public CashSettings removeStamp(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.removeStamp(storeId, principal.getId());
    }

    @PostMapping("/stamp/{stampId}/restore")
    @PreAuthorize(EDIT)
    public CashSettings restoreStamp(@PathVariable UUID storeId, @PathVariable UUID stampId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restoreStamp(storeId, stampId, principal.getId());
    }

    @GetMapping("/stamp/{stampId}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> stamp(@PathVariable UUID storeId, @PathVariable UUID stampId) {
        DocumentService.ExportFile file = service.stampFile(storeId, stampId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PutMapping("/opening-balance")
    @PreAuthorize(EDIT)
    public CashSettings openingBalance(@PathVariable UUID storeId,
            @Valid @RequestBody CashOpeningBalanceRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setOpeningBalance(storeId, request, principal.getId());
    }

    private static ResponseEntity<byte[]> file(DocumentService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
