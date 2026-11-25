package com.intermanage.backend.collector.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.collector.dto.CollectorPromotionDetails;
import com.intermanage.backend.collector.dto.CollectorPromotionMacroRequest;
import com.intermanage.backend.collector.dto.CollectorPromotionResponse;
import com.intermanage.backend.collector.service.CollectorPromotionService;

/**
 * Promocje z kolektorów: uprawnienie jak Ustaw promocje ({@code DATA_ENTRY:VIEW}); makro na komputerze sklepu jak
 * pozostałe makra zlecane z przeglądarki ({@code DATA_ENTRY:EDIT}).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/collector-promotions")
@PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and " + Access.STORE)
public class CollectorPromotionController {

    private final CollectorPromotionService service;

    public CollectorPromotionController(CollectorPromotionService service) {
        this.service = service;
    }

    @GetMapping
    public List<CollectorPromotionResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Promocje, których nikt nie wstawił ani nie ustawił makrem (licznik w menu). */
    @GetMapping("/count")
    public Map<String, Long> count(@PathVariable UUID storeId) {
        return Map.of("waiting", service.countWaiting(storeId));
    }

    @GetMapping("/{id}")
    public CollectorPromotionDetails details(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.details(storeId, id);
    }

    @PostMapping("/{id}/macro")
    @PreAuthorize("hasAuthority('DATA_ENTRY:EDIT') and " + Access.STORE)
    public CollectorPromotionResponse macro(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestBody(required = false) CollectorPromotionMacroRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.runMacro(storeId, id, request == null ? null : request.agentId(), principal.getId());
    }

    @PostMapping("/{id}/inserted")
    public CollectorPromotionResponse inserted(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.markInserted(storeId, id, principal.getId());
    }

    @DeleteMapping("/{id}")
    public List<CollectorPromotionResponse> remove(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    public List<CollectorPromotionResponse> restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }
}
