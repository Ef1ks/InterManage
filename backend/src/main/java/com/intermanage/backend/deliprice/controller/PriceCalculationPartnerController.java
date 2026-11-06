package com.intermanage.backend.deliprice.controller;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.dataentry.dto.CatalogListItem;
import com.intermanage.backend.deliprice.dto.AutoLinkResponse;
import com.intermanage.backend.deliprice.dto.CalculationImportUndoRequest;
import com.intermanage.backend.deliprice.dto.CalculationLinkRequest;
import com.intermanage.backend.deliprice.dto.PartnerCalculation;
import com.intermanage.backend.deliprice.dto.PartnerStore;
import com.intermanage.backend.deliprice.service.PriceCalculationPartnerService;
import com.intermanage.backend.deliprice.service.PriceCalculationService;

import jakarta.validation.Valid;

/**
 * Kalkulacje sklepów zaprzyjaźnionych: podgląd i eksport DELI_PRICES:VIEW, przypisywanie wyrobów do własnych
 * artykułów DELI_PRICES:EDIT. Sklep spoza zaakceptowanych znajomych daje 403.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/price-calculations/partners")
public class PriceCalculationPartnerController {

    private static final String VIEW =
            "hasAuthority('DELI_PRICES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DELI_PRICES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceCalculationPartnerService service;

    public PriceCalculationPartnerController(PriceCalculationPartnerService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<PartnerStore> stores(@PathVariable UUID storeId) {
        return service.stores(storeId);
    }

    /** Propozycje artykułów sklepu dla nazwy wyrobu sąsiada. */
    @GetMapping("/link-suggestions")
    @PreAuthorize(VIEW)
    public List<CatalogListItem> suggestions(@PathVariable UUID storeId, @RequestParam String text) {
        return service.suggestions(storeId, text);
    }

    @GetMapping("/{partnerStoreId}")
    @PreAuthorize(VIEW)
    public List<PartnerCalculation> list(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId) {
        return service.list(storeId, partnerStoreId);
    }

    /** Ceny sąsiada z EAN-ami sklepu: tylko wyroby przypisane do artykułów. */
    @GetMapping("/{partnerStoreId}/export/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId) {
        PriceCalculationService.ExportFile file = service.export(storeId, partnerStoreId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PutMapping("/{partnerStoreId}/links")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void link(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId,
            @Valid @RequestBody CalculationLinkRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        service.link(storeId, partnerStoreId, request, principal.getId());
    }

    @DeleteMapping("/{partnerStoreId}/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void unlink(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.unlink(storeId, id, principal.getId());
    }

    @PostMapping("/{partnerStoreId}/links/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void restore(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId, @PathVariable UUID id) {
        service.restore(storeId, partnerStoreId, id);
    }

    /** Jednym przyciskiem przypisuje pewne pary; resztę przypisuje się ręcznie. */
    @PostMapping("/{partnerStoreId}/links/auto")
    @PreAuthorize(EDIT)
    public AutoLinkResponse autoLink(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.autoLink(storeId, partnerStoreId, principal.getId());
    }

    @PostMapping("/{partnerStoreId}/links/auto/undo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void undoAutoLink(@PathVariable UUID storeId, @PathVariable UUID partnerStoreId,
            @Valid @RequestBody CalculationImportUndoRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        service.undoAutoLink(storeId, request.ids(), principal.getId());
    }
}
