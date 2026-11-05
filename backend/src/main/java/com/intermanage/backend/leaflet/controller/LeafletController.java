package com.intermanage.backend.leaflet.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.leaflet.dto.LeafletLabelCheck;
import com.intermanage.backend.leaflet.dto.LeafletLinkRequest;
import com.intermanage.backend.leaflet.dto.LeafletMatch;
import com.intermanage.backend.leaflet.dto.LeafletResponse;
import com.intermanage.backend.leaflet.dto.LeafletSummary;
import com.intermanage.backend.leaflet.service.LeafletLabelCheckService;
import com.intermanage.backend.leaflet.service.LeafletService;

import jakarta.validation.Valid;

/** Opracowanie gazetki: uprawnienie jak wprowadzanie danych i dostawa z bazy ({@code DATA_ENTRY:VIEW}). */
@RestController
@RequestMapping("/api/stores/{storeId}/leaflets")
public class LeafletController {

    private static final String ACCESS =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    /** Sprawdzarka etykiet służy też pracownikom z sali: wystarczy dostęp do magazynu i produktów. */
    private static final String LABEL_CHECK_ACCESS =
            "(hasAuthority('DATA_ENTRY:VIEW') or hasAuthority('INVENTORY:VIEW')) "
                    + "and @storeAccess.canAccess(authentication, #storeId)";

    private final LeafletService leaflets;
    private final LeafletLabelCheckService labelCheck;

    public LeafletController(LeafletService leaflets, LeafletLabelCheckService labelCheck) {
        this.leaflets = leaflets;
        this.labelCheck = labelCheck;
    }

    @GetMapping
    @PreAuthorize(ACCESS)
    public List<LeafletSummary> list(@PathVariable UUID storeId) {
        return leaflets.list(storeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ACCESS)
    public LeafletResponse upload(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return leaflets.upload(storeId, file, principal.getId());
    }

    @GetMapping("/label-check")
    @PreAuthorize(LABEL_CHECK_ACCESS)
    public LeafletLabelCheck labelCheck(@PathVariable UUID storeId, @RequestParam String query) {
        return labelCheck.check(storeId, query);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ACCESS)
    public LeafletResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return leaflets.get(storeId, id);
    }

    @PutMapping("/links")
    @PreAuthorize(ACCESS)
    public LeafletMatch link(@PathVariable UUID storeId, @Valid @RequestBody LeafletLinkRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return leaflets.link(storeId, request, principal.getId());
    }

    @DeleteMapping("/links/{linkId}")
    @PreAuthorize(ACCESS)
    public LeafletMatch unlink(@PathVariable UUID storeId, @PathVariable UUID linkId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return leaflets.unlink(storeId, linkId, principal.getId());
    }

    @PostMapping("/links/{linkId}/restore")
    @PreAuthorize(ACCESS)
    public LeafletMatch restore(@PathVariable UUID storeId, @PathVariable UUID linkId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return leaflets.restore(storeId, linkId, principal.getId());
    }
}
