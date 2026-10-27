package com.intermanage.backend.controller;

import java.util.List;
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

import com.intermanage.backend.dto.EanChangeResponse;
import com.intermanage.backend.dto.ProductImportOverview;
import com.intermanage.backend.dto.ProductImportResponse;
import com.intermanage.backend.security.AppUserPrincipal;
import com.intermanage.backend.service.ProductImportService;

@RestController
@RequestMapping("/api/stores/{storeId}/imports/products")
public class ProductImportController {

    private final ProductImportService importService;

    public ProductImportController(ProductImportService importService) {
        this.importService = importService;
    }

    /** Liczba produktów sklepu i jego ostatnie importy. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductImportOverview overview(@PathVariable UUID storeId) {
        return importService.overview(storeId);
    }

    @GetMapping("/{id}/ean-changes")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<EanChangeResponse> eanChanges(@PathVariable UUID storeId, @PathVariable UUID id) {
        return importService.eanChanges(storeId, id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public ProductImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return importService.importReport(storeId, file, principal.getId());
    }
}
