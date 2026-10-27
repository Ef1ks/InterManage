package com.intermanage.backend.document.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DeliveryReportClosingResponse;
import com.intermanage.backend.document.dto.DeliveryReportClosingsOverview;
import com.intermanage.backend.document.dto.DeliveryReportExcludedSuppliersRequest;
import com.intermanage.backend.document.dto.DeliveryReportPeriodRequest;
import com.intermanage.backend.document.service.DeliveryReportService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Raport przyjęć PZ za okres (Excel) i zamykanie okresów. Podgląd niczego nie zapisuje; zamknięcie zapisuje, co poszło
 * w raporcie, a późniejsze zmiany przyjęć z zamkniętego okresu trafiają do następnego raportu.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/documents/pz")
public class DeliveryReportController {

    private static final String VIEW =
            "hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DeliveryReportService reports;

    public DeliveryReportController(DeliveryReportService reports) {
        this.reports = reports;
    }

    /** Podgląd raportu za okres (dni wg daty bonu, najwyżej rok) ze zmianami w innych zamkniętych okresach. */
    @GetMapping("/period-report")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> preview(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return download(reports.preview(storeId, from, to));
    }

    /** Same przyjęcia z zamkniętych okresów zmienione po zamknięciu (różnice). */
    @GetMapping("/period-report/changes")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pendingChanges(@PathVariable UUID storeId) {
        return download(reports.pendingChangesFile(storeId));
    }

    @GetMapping("/report-closings")
    @PreAuthorize(VIEW)
    public DeliveryReportClosingsOverview closings(@PathVariable UUID storeId) {
        return reports.overview(storeId);
    }

    /** Zamyka okres; plik zamkniętego raportu pobiera się potem z {@code /report-closings/{id}/xlsx}. */
    @PostMapping("/report-closings")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryReportClosingResponse close(@PathVariable UUID storeId,
            @Valid @RequestBody DeliveryReportPeriodRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return reports.close(storeId, request.from(), request.to(), principal.getId());
    }

    /** Zastępuje listę dostawców pomijanych w raporcie; zwraca przegląd zamknięć z nową listą. */
    @PutMapping("/report-excluded-suppliers")
    @PreAuthorize(EDIT)
    public DeliveryReportClosingsOverview excludedSuppliers(@PathVariable UUID storeId,
            @Valid @RequestBody DeliveryReportExcludedSuppliersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return reports.setExcludedSuppliers(storeId, request.supplierCodes(), principal.getId());
    }

    /** Zamknięty raport tak, jak poszedł (ze zrzutu z chwili zamknięcia). */
    @GetMapping("/report-closings/{id}/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> closed(@PathVariable UUID storeId, @PathVariable UUID id) {
        return download(reports.closedFile(storeId, id));
    }

    /** Cofa ostatnie zamknięcie (znacznik). */
    @DeleteMapping("/report-closings/{id}")
    @PreAuthorize(EDIT)
    public DeliveryReportClosingResponse cancel(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return reports.cancel(storeId, id, principal.getId());
    }

    @PostMapping("/report-closings/{id}/restore")
    @PreAuthorize(EDIT)
    public DeliveryReportClosingResponse restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return reports.restore(storeId, id);
    }

    private static ResponseEntity<byte[]> download(DocumentService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
