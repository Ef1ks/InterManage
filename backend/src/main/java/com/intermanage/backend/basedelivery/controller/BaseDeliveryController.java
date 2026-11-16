package com.intermanage.backend.basedelivery.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.basedelivery.dto.BaseDeliveryResponse;
import com.intermanage.backend.basedelivery.dto.BaseDeliverySummary;
import com.intermanage.backend.basedelivery.dto.BasePortalDocument;
import com.intermanage.backend.basedelivery.dto.MeaConfirmRequest;
import com.intermanage.backend.basedelivery.dto.MeaOverview;
import com.intermanage.backend.basedelivery.dto.TemporaryNoteResponse;
import com.intermanage.backend.basedelivery.service.BaseDeliveryPortalService;
import com.intermanage.backend.basedelivery.service.BaseDeliveryService;
import com.intermanage.backend.basedelivery.service.MeaCodeService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Dostawa z bazy i kody MEA: kto wprowadza faktury ({@code DATA_ENTRY:VIEW}), wprowadza też dokumenty z bazy.
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class BaseDeliveryController {

    private static final String ACCESS =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final BaseDeliveryService deliveries;
    private final MeaCodeService meaCodes;
    private final BaseDeliveryPortalService portal;

    public BaseDeliveryController(BaseDeliveryService deliveries, MeaCodeService meaCodes,
            BaseDeliveryPortalService portal) {
        this.deliveries = deliveries;
        this.meaCodes = meaCodes;
        this.portal = portal;
    }

    @GetMapping("/base-deliveries")
    @PreAuthorize(ACCESS)
    public List<BaseDeliverySummary> list(@PathVariable UUID storeId) {
        return deliveries.list(storeId);
    }

    @PostMapping("/base-deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ACCESS)
    public BaseDeliveryResponse upload(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return deliveries.upload(storeId, file, principal.getId());
    }

    /** Bony z portalu Grupy Muszkieterów z ostatnich {@code days} dni (0 = dziś), z oznaczeniem już wgranych. */
    @GetMapping("/base-deliveries/portal")
    @PreAuthorize(ACCESS)
    public List<BasePortalDocument> portalList(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "7") int days) {
        return portal.list(storeId, days);
    }

    /** Pobranie bonu z portalu i zapis jak wgranego pliku. */
    @PostMapping("/base-deliveries/portal/{number}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ACCESS)
    public BaseDeliveryResponse portalDownload(@PathVariable UUID storeId, @PathVariable String number,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return portal.download(storeId, number, principal.getId());
    }

    /** „Tymczasowy bon dostawy” z Mercalys do „Znajdź różnice”: tylko odczyt pliku, nic nie zapisuje. */
    @PostMapping("/base-deliveries/temporary-notes")
    @PreAuthorize(ACCESS)
    public TemporaryNoteResponse readTemporaryNote(@PathVariable UUID storeId,
            @RequestParam("file") MultipartFile file) {
        return deliveries.readTemporaryNote(storeId, file);
    }

    @GetMapping("/base-deliveries/{id}")
    @PreAuthorize(ACCESS)
    public BaseDeliveryResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return deliveries.get(storeId, id);
    }

    @GetMapping("/base-deliveries/{id}/short-dates/pdf")
    @PreAuthorize(ACCESS)
    public ResponseEntity<byte[]> shortDatesPdf(@PathVariable UUID storeId, @PathVariable UUID id) {
        DocumentService.ExportFile file = deliveries.shortDatesPdf(storeId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }

    /** Plik dostawy dla programu sklepu (jak plik bazy LIVRJPOL…TXT) dla jednego dostawcy bonu (538, 88110). */
    @GetMapping("/base-deliveries/{id}/note-file")
    @PreAuthorize(ACCESS)
    public ResponseEntity<byte[]> noteFile(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestParam String supplier) {
        DocumentService.ExportFile file = deliveries.noteFile(storeId, id, supplier);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.TEXT_PLAIN)
                .body(file.content());
    }

    @GetMapping("/mea-codes")
    @PreAuthorize(ACCESS)
    public MeaOverview meaCodes(@PathVariable UUID storeId) {
        return meaCodes.overview(storeId);
    }

    @PostMapping("/mea-codes/confirmations")
    @PreAuthorize(ACCESS)
    public MeaOverview confirm(@PathVariable UUID storeId, @Valid @RequestBody MeaConfirmRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return meaCodes.confirm(storeId, request, principal.getId());
    }

    @DeleteMapping("/mea-codes/confirmations/{confirmationId}")
    @PreAuthorize(ACCESS)
    public MeaOverview deleteConfirmation(@PathVariable UUID storeId, @PathVariable UUID confirmationId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return meaCodes.delete(storeId, confirmationId, principal.getId());
    }

    @PostMapping("/mea-codes/confirmations/{confirmationId}/restore")
    @PreAuthorize(ACCESS)
    public MeaOverview restoreConfirmation(@PathVariable UUID storeId, @PathVariable UUID confirmationId) {
        return meaCodes.restore(storeId, confirmationId);
    }
}
