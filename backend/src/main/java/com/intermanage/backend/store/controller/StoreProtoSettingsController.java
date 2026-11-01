package com.intermanage.backend.store.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.store.dto.ProtoSettingsRequest;
import com.intermanage.backend.store.dto.ProtoSettingsResponse;
import com.intermanage.backend.store.service.StoreProtoSettingsService;

import jakarta.validation.Valid;

/**
 * Pliki PROTO sklepu. Odczyt dla każdego z dostępem do sklepu (folder podpowiadają garmaż, przyjęcia MM i
 * wprowadzanie danych), zmiana dla osób, które te pliki zapisują.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/proto-settings")
public class StoreProtoSettingsController {

    private final StoreProtoSettingsService service;

    public StoreProtoSettingsController(StoreProtoSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@storeAccess.canAccess(authentication, #storeId)")
    public ProtoSettingsResponse get(@PathVariable UUID storeId) {
        return service.get(storeId);
    }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('DATA_ENTRY:EDIT', 'DOCUMENTS:EDIT', 'DELI_ORDERS:EDIT')"
            + " and @storeAccess.canAccess(authentication, #storeId)")
    public ProtoSettingsResponse save(@PathVariable UUID storeId, @Valid @RequestBody ProtoSettingsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request, principal.getId());
    }
}
