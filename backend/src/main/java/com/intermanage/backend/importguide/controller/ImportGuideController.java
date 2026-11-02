package com.intermanage.backend.importguide.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.importguide.dto.ImportGuideRequest;
import com.intermanage.backend.importguide.dto.ImportGuideResponse;
import com.intermanage.backend.importguide.entity.ImportReportKind;
import com.intermanage.backend.importguide.service.ImportGuideService;

import jakarta.validation.Valid;

/**
 * Instrukcje do raportów z Importu danych: czyta każdy z {@code IMPORTS:VIEW}, zmienia tylko właściciel (reguła dla
 * /api/settings/** w SecurityConfig).
 */
@RestController
public class ImportGuideController {

    private final ImportGuideService service;

    public ImportGuideController(ImportGuideService service) {
        this.service = service;
    }

    @GetMapping("/api/import-guides")
    @PreAuthorize("hasAuthority('IMPORTS:VIEW')")
    public List<ImportGuideResponse> list() {
        return service.list();
    }

    @PutMapping("/api/settings/import-guides/{kind}")
    public List<ImportGuideResponse> save(@PathVariable ImportReportKind kind,
            @Valid @RequestBody ImportGuideRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(kind, request, principal.getId());
    }
}
