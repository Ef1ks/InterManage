package com.intermanage.backend.product.controller;

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
import com.intermanage.backend.product.dto.ProductCodeImportOverview;
import com.intermanage.backend.product.dto.ProductCodeImportResponse;
import com.intermanage.backend.product.service.ProductCodeImportService;

/** Multikody ("Szczegóły informacji kodu sprzedaży") wgrywane w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/product-codes")
public class ProductCodeImportController {

    private final ProductCodeImportService productCodeImportService;

    public ProductCodeImportController(ProductCodeImportService productCodeImportService) {
        this.productCodeImportService = productCodeImportService;
    }

    /** Ile kodów i artykułów jest w najnowszych multikodach sklepu i ostatnie wgrania. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductCodeImportOverview imports(@PathVariable UUID storeId) {
        return productCodeImportService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductCodeImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return productCodeImportService.importReport(storeId, file, principal.getId());
    }
}
