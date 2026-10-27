package com.intermanage.backend.supplier.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.supplier.dto.SupplierImportOverview;
import com.intermanage.backend.supplier.dto.SupplierImportResponse;
import com.intermanage.backend.supplier.service.SupplierService;

/** Raport "Lista dostawców" wgrywany w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/suppliers")
public class SupplierImportController {

    private final SupplierService supplierService;

    public SupplierImportController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    /** Liczba dostawców sklepu i ostatnie wgrania listy. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public SupplierImportOverview imports(@PathVariable UUID storeId) {
        return supplierService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public SupplierImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return supplierService.importReport(storeId, file, principal.getId());
    }
}
