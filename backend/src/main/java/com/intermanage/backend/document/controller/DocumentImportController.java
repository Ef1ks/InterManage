package com.intermanage.backend.document.controller;

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

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.DocumentImportResponse;
import com.intermanage.backend.document.service.DocumentService;

/** Raport Wejścia/Wyjścia (dokumenty magazynowe) wgrywany w narzędziu Import danych, obok raportu produktów. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/documents")
public class DocumentImportController {

    private final DocumentService documentService;

    public DocumentImportController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** Ostatnie wgrania raportu. */
    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<DocumentImportResponse> imports(@PathVariable UUID storeId) {
        return documentService.imports(storeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public DocumentImportResponse importReport(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return documentService.importReport(storeId, file, principal.getId());
    }
}
