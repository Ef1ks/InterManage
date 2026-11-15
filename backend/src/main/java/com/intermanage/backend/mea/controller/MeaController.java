package com.intermanage.backend.mea.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.mea.component.MurphysoftClient;
import com.intermanage.backend.mea.dto.MeaDocumentResponse;
import com.intermanage.backend.mea.dto.MeaListResponse;
import com.intermanage.backend.mea.dto.MeaSettingsRequest;
import com.intermanage.backend.mea.dto.MeaSettingsResponse;
import com.intermanage.backend.mea.service.MeaDocumentService;
import com.intermanage.backend.mea.service.MeaService;

/**
 * Moduł MEA: lista MEA z portalu Grupy Muszkieterów dla sklepu i zapis kluczy API sklepu. Uprawnienie jak przy
 * dostawie z bazy - kto wprowadza faktury i dokumenty z bazy, widzi też MEA; zapis kluczy wymaga EDIT.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/mea")
public class MeaController {

    private final MeaService service;
    private final MeaDocumentService documents;

    public MeaController(MeaService service, MeaDocumentService documents) {
        this.service = service;
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaListResponse list(@PathVariable UUID storeId) {
        try {
            MeaListResponse list = service.list(storeId);
            // Nowe MEA trafiają od razu do bazy, a formularze pobierze zadanie w tle albo otwarcie dokumentu.
            documents.register(list.items());
            return list;
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping("/documents/{number}")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaDocumentResponse document(@PathVariable UUID storeId, @PathVariable String number) {
        try {
            return documents.document(storeId, number);
        } catch (MurphysoftClient.Unavailable exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaSettingsResponse settings(@PathVariable UUID storeId) {
        return service.settings(storeId);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('DATA_ENTRY:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public MeaSettingsResponse save(@PathVariable UUID storeId, @RequestBody MeaSettingsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request, principal.getId());
    }
}
