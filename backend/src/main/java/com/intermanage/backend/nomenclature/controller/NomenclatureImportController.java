package com.intermanage.backend.nomenclature.controller;

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
import com.intermanage.backend.nomenclature.dto.NomenclatureImportOverview;
import com.intermanage.backend.nomenclature.dto.NomenclatureImportResponse;
import com.intermanage.backend.nomenclature.service.NomenclatureService;

/** Raport "Lista nomenklatur" (PDF) wgrywany w narzędziu Import danych. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/nomenclatures")
public class NomenclatureImportController {

    private final NomenclatureService nomenclatureService;

    public NomenclatureImportController(NomenclatureService nomenclatureService) {
        this.nomenclatureService = nomenclatureService;
    }

    /** Ile rejonów, rodzin i podrodzin ma sklep i ostatnie wgrania listy. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public NomenclatureImportOverview imports(@PathVariable UUID storeId) {
        return nomenclatureService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public NomenclatureImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return nomenclatureService.importReport(storeId, file, principal.getId());
    }
}
