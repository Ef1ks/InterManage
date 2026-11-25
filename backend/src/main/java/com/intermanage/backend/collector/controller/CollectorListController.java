package com.intermanage.backend.collector.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.collector.dto.CollectorListDetails;
import com.intermanage.backend.collector.dto.CollectorListResponse;
import com.intermanage.backend.collector.dto.CollectorProtocolRequest;
import com.intermanage.backend.collector.service.CollectorListService;
import com.intermanage.backend.common.component.ProtoFile;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService;
import com.intermanage.backend.store.entity.ProtocolKind;

import jakarta.validation.Valid;

/** Dane z kolektorów: uprawnienie jak protokoły ({@code DATA_ENTRY}); to praca narzędziem, więc wystarcza podgląd. */
@RestController
@RequestMapping("/api/stores/{storeId}/collector-lists")
@PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and " + Access.STORE)
public class CollectorListController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CollectorListService service;

    public CollectorListController(CollectorListService service) {
        this.service = service;
    }

    @GetMapping
    public List<CollectorListResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Listy czekające na wybór protokołu (licznik w menu). */
    @GetMapping("/count")
    public Map<String, Long> count(@PathVariable UUID storeId) {
        return Map.of("waiting", service.countWaiting(storeId));
    }

    /** Zawartość wybranych list razem. */
    @GetMapping("/details")
    public CollectorListDetails details(@PathVariable UUID storeId, @RequestParam List<UUID> ids) {
        return service.details(storeId, ids);
    }

    /** Plik protokołu z wybranych list; po zapisaniu go w folderze {@code .../saved}. */
    @PostMapping("/protocols/{kind}")
    public ResponseEntity<byte[]> protocol(@PathVariable UUID storeId, @PathVariable ProtocolKind kind,
            @Valid @RequestBody CollectorProtocolRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        DataEntryProtocolService.ProtocolFile file = service.protocol(storeId, kind, request.ids(),
                principal.getUsername());
        return file(file.content(), file.fileName(), MediaType.parseMediaType(ProtoFile.CONTENT_TYPE), false);
    }

    @PostMapping("/protocols/{kind}/saved")
    public List<CollectorListResponse> saved(@PathVariable UUID storeId, @PathVariable ProtocolKind kind,
            @Valid @RequestBody CollectorProtocolRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.markSaved(storeId, kind, request.ids(), request.fileName(), principal.getId());
    }

    @DeleteMapping("/{listId}/protocols/{protocolId}")
    public List<CollectorListResponse> undoProtocol(@PathVariable UUID storeId, @PathVariable UUID listId,
            @PathVariable UUID protocolId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.undoProtocol(storeId, listId, protocolId, principal.getId());
    }

    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @RequestParam List<UUID> ids) {
        return file(service.xlsx(storeId, ids), "Lista z kolektora.xlsx", XLSX, false);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @RequestParam List<UUID> ids,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(service.pdf(storeId, ids, principal.getUsername()), "Lista z kolektora.pdf",
                MediaType.APPLICATION_PDF, true);
    }

    @DeleteMapping("/{listId}")
    public List<CollectorListResponse> remove(@PathVariable UUID storeId, @PathVariable UUID listId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, listId, principal.getId());
    }

    @PostMapping("/{listId}/restore")
    public List<CollectorListResponse> restore(@PathVariable UUID storeId, @PathVariable UUID listId) {
        return service.restore(storeId, listId);
    }

    private static ResponseEntity<byte[]> file(byte[] content, String name, MediaType type, boolean inline) {
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).contentType(type)
                .body(content);
    }
}
