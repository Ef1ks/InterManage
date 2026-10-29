package com.intermanage.backend.document.controller;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DocumentResponse;
import com.intermanage.backend.document.dto.EnteredRequest;
import com.intermanage.backend.document.dto.IncomingTransfer;
import com.intermanage.backend.document.dto.IncomingTransfers;
import com.intermanage.backend.document.dto.ReceiveTransferRequest;
import com.intermanage.backend.document.dto.SetTransferTargetRequest;
import com.intermanage.backend.document.service.DocumentTransferService;
import com.intermanage.backend.document.service.DocumentTransferService.ExportFile;

import jakarta.validation.Valid;

/**
 * Ruchy międzymagazynowe dla sklepów zaprzyjaźnionych: sklep wysyłający wskazuje sklep docelowy w dokumencie MM,
 * a sklep docelowy widzi go w "Do wprowadzenia" ({@code incoming-transfers}) i oznacza jako wprowadzony.
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class DocumentTransferController {

    private static final String VIEW =
            "hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DocumentTransferService transferService;

    public DocumentTransferController(DocumentTransferService transferService) {
        this.transferService = transferService;
    }

    /** Wskazuje sklep zaprzyjaźniony, dla którego wystawiono dokument MM, albo go zmienia. */
    @PutMapping("/documents/{type}/{id}/transfer-target")
    @PreAuthorize(EDIT)
    public DocumentResponse setTarget(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @Valid @RequestBody SetTransferTargetRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return transferService.setTarget(storeId, type, id, request.storeId(), principal.getId());
    }

    /** Cofa wskazanie (znacznik, do przywrócenia). */
    @DeleteMapping("/documents/{type}/{id}/transfer-target")
    @PreAuthorize(EDIT)
    public DocumentResponse cancelTarget(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return transferService.cancel(storeId, type, id, principal.getId());
    }

    @PostMapping("/documents/{type}/{id}/transfer-target/restore")
    @PreAuthorize(EDIT)
    public DocumentResponse restoreTarget(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id) {
        return transferService.restore(storeId, type, id);
    }

    /** Dokumenty MM innych sklepów wystawione dla tego sklepu: do wprowadzenia i ostatnio wprowadzone. */
    @GetMapping("/incoming-transfers")
    @PreAuthorize(VIEW)
    public IncomingTransfers incoming(@PathVariable UUID storeId) {
        return transferService.incoming(storeId);
    }

    @GetMapping("/incoming-transfers/count")
    @PreAuthorize(VIEW)
    public long pendingCount(@PathVariable UUID storeId) {
        return transferService.pendingCount(storeId);
    }

    @GetMapping("/incoming-transfers/{id}")
    @PreAuthorize(VIEW)
    public IncomingTransfer incomingTransfer(@PathVariable UUID storeId, @PathVariable UUID id) {
        return transferService.incomingTransfer(storeId, id);
    }

    /** Ilości, które naprawdę przyjechały; zapis oznacza dokument jako wprowadzony. */
    @PostMapping("/incoming-transfers/{id}/receive")
    @PreAuthorize(EDIT)
    public IncomingTransfer receive(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody ReceiveTransferRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return transferService.receive(storeId, id, request.items(), principal.getId());
    }

    /** Plik przyjęcia w przyjętych ilościach: {@code proto} (PROTO6, .Traite0) albo {@code xlsx}. */
    @GetMapping("/incoming-transfers/{id}/export/{format}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable String format, @AuthenticationPrincipal AppUserPrincipal principal) {
        ExportFile file = transferService.export(storeId, id, format, principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /** Oznacza dokument jako wprowadzony w tym sklepie albo cofa oznaczenie. */
    @PutMapping("/incoming-transfers/{id}/entered")
    @PreAuthorize(EDIT)
    public IncomingTransfer markEntered(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody EnteredRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return transferService.markEntered(storeId, id, request.entered(), principal.getId());
    }
}
