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
import com.intermanage.backend.common.component.ChunkedUploads;
import com.intermanage.backend.product.dto.DepositKindImportOverview;
import com.intermanage.backend.product.dto.DepositKindImportResponse;
import com.intermanage.backend.product.service.DepositKindService;

/** Raport "Lista opakowań zwrotnych" (PDF) wgrywany w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/deposit-kinds")
public class DepositKindImportController {

    private final DepositKindService depositKindService;
    private final ChunkedUploads uploads;

    public DepositKindImportController(DepositKindService depositKindService, ChunkedUploads uploads) {
        this.depositKindService = depositKindService;
        this.uploads = uploads;
    }

    /** Opakowania zwrotne sklepu i ostatnie wgrania ich listy. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public DepositKindImportOverview imports(@PathVariable UUID storeId) {
        return depositKindService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public DepositKindImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return depositKindService.importReport(storeId, file, principal.getId());
    }

    /** Plik przysłany w kawałkach (`/imports/uploads`), dalej jak zwykłe wgranie. */
    @PostMapping("/uploads/{uploadId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public DepositKindImportResponse importUpload(@PathVariable UUID storeId, @PathVariable UUID uploadId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        try (ChunkedUploads.UploadedFile file = uploads.take(ChunkedUploads.owner(storeId, principal.getId()), uploadId)) {
            return depositKindService.importReport(storeId, file, principal.getId());
        }
    }
}
