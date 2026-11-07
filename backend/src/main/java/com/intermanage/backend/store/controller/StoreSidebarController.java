package com.intermanage.backend.store.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.store.dto.SidebarOrderRequest;
import com.intermanage.backend.store.dto.SidebarOrderResponse;
import com.intermanage.backend.store.service.StoreSidebarService;

import jakarta.validation.Valid;

/**
 * Kolejność kategorii menu bocznego (Ustawienia sklepu). Zmienia ją każdy, kto ma dostęp do Ustawień sklepu; odczyt
 * idzie w {@code /auth/me}.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/sidebar-order")
public class StoreSidebarController {

    private final StoreSidebarService service;

    public StoreSidebarController(StoreSidebarService service) {
        this.service = service;
    }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('SALES:EDIT', 'DOCUMENTS:EDIT', 'DATA_ENTRY:EDIT', 'DELI_ORDERS:EDIT')"
            + " and @storeAccess.canAccess(authentication, #storeId)")
    public SidebarOrderResponse save(@PathVariable UUID storeId, @Valid @RequestBody SidebarOrderRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request.groups(), principal.getId());
    }
}
