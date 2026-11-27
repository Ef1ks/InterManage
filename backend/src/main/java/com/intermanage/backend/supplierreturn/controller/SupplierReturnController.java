package com.intermanage.backend.supplierreturn.controller;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.supplierreturn.dto.SupplierReturnDtos;
import com.intermanage.backend.supplierreturn.service.SupplierReturnService;

import jakarta.validation.Valid;

/**
 * Zwroty do dostawcy (Zakupy). Uprawnienie jak wprowadzanie danych i listy z kolektora: kto przyjmuje towar, ten go
 * też oddaje, więc cała praca narzędziem idzie z podglądem.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/supplier-returns")
@PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and " + Access.STORE)
public class SupplierReturnController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SupplierReturnService service;

    public SupplierReturnController(SupplierReturnService service) {
        this.service = service;
    }

    @GetMapping
    public List<SupplierReturnDtos.Summary> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Otwarte zwroty (licznik w menu). */
    @GetMapping("/count")
    public Map<String, Long> count(@PathVariable UUID storeId) {
        return Map.of("open", service.countOpen(storeId));
    }

    @PostMapping("/from-collector")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierReturnDtos.Details fromCollector(@PathVariable UUID storeId,
            @Valid @RequestBody SupplierReturnDtos.FromCollectorRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.fromCollector(storeId, request, principal.getId());
    }

    @PostMapping("/from-protocols")
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierReturnDtos.Details fromProtocols(@PathVariable UUID storeId,
            @Valid @RequestBody SupplierReturnDtos.FromProtocolsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.fromProtocols(storeId, request, principal.getId());
    }

    @GetMapping("/{id}")
    public SupplierReturnDtos.Details details(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.details(storeId, id);
    }

    /** Bony dostawy, bony korekt i korekty z KSeF do podpięcia, z liczbą artykułów wspólnych ze zwrotem. */
    @GetMapping("/{id}/candidates")
    public SupplierReturnDtos.Candidates candidates(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.candidates(storeId, id);
    }

    @PutMapping("/{id}/status")
    public SupplierReturnDtos.Details status(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody SupplierReturnDtos.StatusRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setStatus(storeId, id, request, principal.getId());
    }

    @PutMapping("/{id}/supplier")
    public SupplierReturnDtos.Details supplier(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody SupplierReturnDtos.SupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setSupplier(storeId, id, request, principal.getId());
    }

    @PutMapping("/{id}/note")
    public SupplierReturnDtos.Details note(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody SupplierReturnDtos.NoteRequest request) {
        return service.setNote(storeId, id, request);
    }

    @PutMapping("/{id}/delivery")
    public SupplierReturnDtos.Details delivery(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody SupplierReturnDtos.AttachRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setDelivery(storeId, id, request, principal.getId());
    }

    @PutMapping("/{id}/correction")
    public SupplierReturnDtos.Details correction(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody SupplierReturnDtos.AttachRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setCorrection(storeId, id, request, principal.getId());
    }

    @PutMapping("/{id}/invoice")
    public SupplierReturnDtos.Details invoice(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody SupplierReturnDtos.AttachRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setInvoice(storeId, id, request, principal.getId());
    }

    @GetMapping("/{id}/xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable UUID storeId, @PathVariable UUID id) {
        return file(service.xlsx(storeId, id), "Zwrot do dostawcy.xlsx", XLSX, false);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return file(service.pdf(storeId, id, principal.getUsername()), "Zwrot do dostawcy.pdf",
                MediaType.APPLICATION_PDF, true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.remove(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        service.restore(storeId, id);
    }

    private static ResponseEntity<byte[]> file(byte[] content, String name, MediaType type, boolean inline) {
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).contentType(type)
                .body(content);
    }
}
