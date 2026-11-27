package com.intermanage.backend.dataentry.controller;

import java.util.List;
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
import com.intermanage.backend.dataentry.dto.SupplierMarginRequest;
import com.intermanage.backend.dataentry.dto.SupplierMarginResponse;
import com.intermanage.backend.dataentry.service.SupplierMarginService;

import jakarta.validation.Valid;

/**
 * Stałe marże dostawców: czyta je wprowadzanie danych i karta dostawcy, ustawia osoba z {@code DATA_ENTRY:EDIT}
 * albo {@code SUPPLIERS:EDIT}.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/supplier-margins")
public class SupplierMarginController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAnyAuthority('DATA_ENTRY:VIEW', 'SUPPLIERS:VIEW')" + STORE;
    private static final String EDIT = "hasAnyAuthority('DATA_ENTRY:EDIT', 'SUPPLIERS:EDIT')" + STORE;

    private final SupplierMarginService service;

    public SupplierMarginController(SupplierMarginService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<SupplierMarginResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PutMapping("/{supplierId}")
    @PreAuthorize(EDIT)
    public List<SupplierMarginResponse> save(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @Valid @RequestBody SupplierMarginRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, supplierId, request, principal.getId());
    }
}
