package com.intermanage.backend.dataentry.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.dataentry.dto.SupplierSalePriceRequest;
import com.intermanage.backend.dataentry.dto.SupplierSalePriceResponse;
import com.intermanage.backend.dataentry.service.SupplierSalePriceService;

import jakarta.validation.Valid;

/**
 * Stałe ceny sprzedaży dostawców: ustawia je osoba z {@code DATA_ENTRY:EDIT} (Ustawienia sklepu), a czyta
 * wprowadzanie danych.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/supplier-sale-prices")
public class SupplierSalePriceController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DATA_ENTRY:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final SupplierSalePriceService service;

    public SupplierSalePriceController(SupplierSalePriceService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<SupplierSalePriceResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PutMapping("/{supplierId}")
    @PreAuthorize(EDIT)
    public List<SupplierSalePriceResponse> save(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @Valid @RequestBody SupplierSalePriceRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, supplierId, request, principal.getId());
    }

    @DeleteMapping("/{supplierId}")
    @PreAuthorize(EDIT)
    public List<SupplierSalePriceResponse> delete(@PathVariable UUID storeId, @PathVariable UUID supplierId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.delete(storeId, supplierId, principal.getId());
    }
}
