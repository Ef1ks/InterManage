package com.intermanage.backend.store.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.store.dto.InviteStorePartnerRequest;
import com.intermanage.backend.store.dto.StorePartnersResponse;
import com.intermanage.backend.store.service.StorePartnerService;

import jakarta.validation.Valid;

/**
 * Sklepy zaprzyjaźnione (Ustawienia sklepu). Znajomość służy dokumentom (ruch międzymagazynowy dla innego sklepu),
 * więc zarządza nią osoba z {@code DOCUMENTS:EDIT} w danym sklepie.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/partners")
public class StorePartnerController {

    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final StorePartnerService partnerService;

    public StorePartnerController(StorePartnerService partnerService) {
        this.partnerService = partnerService;
    }

    @GetMapping
    @PreAuthorize(EDIT)
    public StorePartnersResponse partners(@PathVariable UUID storeId) {
        return partnerService.partners(storeId);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public StorePartnersResponse invite(@PathVariable UUID storeId,
            @Valid @RequestBody InviteStorePartnerRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return partnerService.invite(storeId, request.storeId(), principal.getId());
    }

    @PostMapping("/{partnerId}/accept")
    @PreAuthorize(EDIT)
    public StorePartnersResponse accept(@PathVariable UUID storeId, @PathVariable UUID partnerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return partnerService.accept(storeId, partnerId, principal.getId());
    }

    /** Odrzuca albo cofa zaproszenie, albo kończy znajomość; znacznik, "Przywróć" to {@code restore}. */
    @DeleteMapping("/{partnerId}")
    @PreAuthorize(EDIT)
    public StorePartnersResponse remove(@PathVariable UUID storeId, @PathVariable UUID partnerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return partnerService.remove(storeId, partnerId, principal.getId());
    }

    @PostMapping("/{partnerId}/restore")
    @PreAuthorize(EDIT)
    public StorePartnersResponse restore(@PathVariable UUID storeId, @PathVariable UUID partnerId) {
        return partnerService.restore(storeId, partnerId);
    }
}
