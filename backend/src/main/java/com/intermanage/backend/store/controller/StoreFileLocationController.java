package com.intermanage.backend.store.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.store.dto.StoreFileLocationsRequest;
import com.intermanage.backend.store.dto.StoreFileLocationsResponse;
import com.intermanage.backend.store.service.StoreFileLocationService;

import jakarta.validation.Valid;

/**
 * Ścieżki plików sklepu (foldery raportów, foldery zapisu, ścieżki makr). Odczyt i zmiana dla każdego z dostępem do
 * sklepu, bo to podpowiedzi przy wyborze folderu w przeglądarce, a wybiera go każdy, kto zapisuje albo wgrywa pliki.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/file-locations")
public class StoreFileLocationController {

    private final StoreFileLocationService service;

    public StoreFileLocationController(StoreFileLocationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Access.STORE)
    public StoreFileLocationsResponse get(@PathVariable UUID storeId) {
        return service.get(storeId);
    }

    @PutMapping
    @PreAuthorize(Access.STORE)
    public StoreFileLocationsResponse save(@PathVariable UUID storeId,
            @Valid @RequestBody StoreFileLocationsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request.locations(), principal.getId());
    }
}
