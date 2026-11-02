package com.intermanage.backend.promotion.controller;

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
import com.intermanage.backend.promotion.dto.PromotionImportOverview;
import com.intermanage.backend.promotion.dto.PromotionImportResponse;
import com.intermanage.backend.promotion.service.PromotionImportService;

/** Raport "Lista bieżących i przyszłych promocji" (Taryfy.pdf) wgrywany w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/promotions")
public class PromotionImportController {

    private final PromotionImportService promotionImportService;

    public PromotionImportController(PromotionImportService promotionImportService) {
        this.promotionImportService = promotionImportService;
    }

    /** Liczba trwających i przyszłych promocji sklepu i ostatnie wgrania raportu. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public PromotionImportOverview imports(@PathVariable UUID storeId) {
        return promotionImportService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public PromotionImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return promotionImportService.importReport(storeId, file, principal.getId());
    }
}
