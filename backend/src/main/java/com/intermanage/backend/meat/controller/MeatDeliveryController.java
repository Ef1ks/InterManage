package com.intermanage.backend.meat.controller;

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
import com.intermanage.backend.document.service.DocumentService;
import com.intermanage.backend.meat.dto.MeatArticle;
import com.intermanage.backend.meat.dto.MeatDeliveryRequest;
import com.intermanage.backend.meat.dto.MeatDeliveryResponse;
import com.intermanage.backend.meat.dto.MeatDeliverySummary;
import com.intermanage.backend.meat.dto.MeatReport;
import com.intermanage.backend.meat.dto.MeatScanResult;
import com.intermanage.backend.meat.service.MeatDeliveryService;
import com.intermanage.backend.meat.service.MeatReportService;

import jakarta.validation.Valid;

/**
 * Zakup mięsa: dokumenty WZ dostawców (odczyt skanu przez AI, zapis, usuwanie ze znacznikiem) i porównanie cen
 * zakupu i sprzedaży w sklepach. Raport obejmuje sklepy, do których użytkownik ma dostęp.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/meat-deliveries")
public class MeatDeliveryController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAuthority('MEAT_PURCHASES:VIEW')" + STORE;
    private static final String EDIT = "hasAuthority('MEAT_PURCHASES:EDIT')" + STORE;

    private final MeatDeliveryService service;
    private final MeatReportService reports;

    public MeatDeliveryController(MeatDeliveryService service, MeatReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<MeatDeliverySummary> list(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(storeId, from, to);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public MeatDeliveryResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.get(storeId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public MeatDeliveryResponse create(@PathVariable UUID storeId, @Valid @RequestBody MeatDeliveryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public MeatDeliveryResponse update(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody MeatDeliveryRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.update(storeId, id, request, principal.getId());
    }

    /** Usunięcie (znacznik); „Przywróć” to {@code POST …/{id}/restore}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(EDIT)
    public MeatDeliveryResponse restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }

    /** Odczyt skanu albo zdjęcia WZ przez AI; zapisuje tylko skan, dokument zapisuje dopiero {@code POST}. */
    @PostMapping("/scan")
    @PreAuthorize(EDIT)
    public MeatScanResult scan(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.scan(storeId, file, principal.getId());
    }

    @GetMapping("/scans/{scanId}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> scanFile(@PathVariable UUID storeId, @PathVariable UUID scanId) {
        DocumentService.ExportFile file = service.scanFile(storeId, scanId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @GetMapping("/articles")
    @PreAuthorize(VIEW)
    public List<MeatArticle> articles(@PathVariable UUID storeId) {
        return service.articles(storeId);
    }

    @GetMapping("/report")
    @PreAuthorize(VIEW)
    public MeatReport report(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "SPLIT") MeatReportService.Mode mode,
            @RequestParam(required = false) List<UUID> stores,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return reports.report(principal.getId(), stores, from, to, mode);
    }
}
