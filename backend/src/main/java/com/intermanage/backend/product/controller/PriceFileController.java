package com.intermanage.backend.product.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.product.dto.PendingPriceFilesRequest;
import com.intermanage.backend.product.dto.PendingPriceFilesResponse;
import com.intermanage.backend.product.dto.PriceFileOverview;
import com.intermanage.backend.product.dto.PriceFileUploadResponse;
import com.intermanage.backend.product.service.PriceFileService;

import jakarta.validation.Valid;

/**
 * Pliki TRACEART (zmiany cen na kasie) z folderu wybranego w przeglądarce. Wysyła je sama przeglądarka co kilka minut,
 * więc wystarczy dostęp do wprowadzania danych albo importu.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/price-files")
public class PriceFileController {

    private static final String ACCESS = "(hasAuthority('DATA_ENTRY:VIEW') or hasAuthority('IMPORTS:EDIT')) "
            + "and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceFileService priceFileService;

    public PriceFileController(PriceFileService priceFileService) {
        this.priceFileService = priceFileService;
    }

    @GetMapping
    @PreAuthorize(ACCESS)
    public PriceFileOverview overview(@PathVariable UUID storeId) {
        return priceFileService.overview(storeId);
    }

    @PostMapping("/pending")
    @PreAuthorize(ACCESS)
    public PendingPriceFilesResponse pending(@PathVariable UUID storeId,
            @Valid @RequestBody PendingPriceFilesRequest request) {
        return priceFileService.pending(storeId, request.fileNames());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ACCESS)
    public PriceFileUploadResponse upload(@PathVariable UUID storeId, @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return priceFileService.upload(storeId, files, principal.getId());
    }
}
