package com.intermanage.backend.supplier.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.supplier.dto.CreateSupplierRequest;
import com.intermanage.backend.supplier.dto.SupplierResponse;
import com.intermanage.backend.supplier.service.SupplierService;

import jakarta.validation.Valid;

/**
 * Dostawcy sklepu do wyboru przy rejestrowaniu gratisu w module Dokumenty: z listy z systemu sklepu
 * ({@link SupplierImportController}) i dodani ręcznie.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<SupplierResponse> list(@PathVariable UUID storeId) {
        return supplierService.list(storeId);
    }

    /** Dostawca, którego nie ma w systemie sklepu - jest tylko w aplikacji. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public SupplierResponse create(@PathVariable UUID storeId, @Valid @RequestBody CreateSupplierRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return supplierService.create(storeId, request, principal.getId());
    }
}
