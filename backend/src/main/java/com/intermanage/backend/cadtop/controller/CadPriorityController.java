package com.intermanage.backend.cadtop.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.cadtop.dto.CadPriorityReport;
import com.intermanage.backend.cadtop.dto.CadPriorityRequest;
import com.intermanage.backend.cadtop.service.CadPriorityService;

import jakarta.validation.Valid;

/**
 * ITM8 z cadencier do ustawienia jako priorytetowe (Zakupy).
 *
 * <pre>
 * GET  /api/stores/{storeId}/cadencier-priorities           lista artykułów ze stanem zlecenia
 * POST /api/stores/{storeId}/cadencier-priorities/macro     {items:[{itm8, topItm8}]} makro do Mercalys
 * POST /api/stores/{storeId}/cadencier-priorities/runs      to samo, ustawione ręcznie
 * POST /api/stores/{storeId}/cadencier-priorities/runs/undo|restore   {ids}
 * </pre>
 */
@RestController
@RequestMapping("/api/stores/{storeId}/cadencier-priorities")
public class CadPriorityController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final CadPriorityService service;

    public CadPriorityController(CadPriorityService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public CadPriorityReport report(@PathVariable UUID storeId) {
        return service.report(storeId);
    }

    @PostMapping("/macro")
    @PreAuthorize(VIEW)
    public CadPriorityReport.RunResult macro(@PathVariable UUID storeId,
            @Valid @RequestBody CadPriorityRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.macro(storeId, request, principal.getId());
    }

    @PostMapping("/runs")
    @PreAuthorize(VIEW)
    public CadPriorityReport.RunResult mark(@PathVariable UUID storeId,
            @Valid @RequestBody CadPriorityRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.mark(storeId, request, principal.getId());
    }

    @PostMapping("/runs/undo")
    @PreAuthorize(VIEW)
    public CadPriorityReport undo(@PathVariable UUID storeId, @Valid @RequestBody CadPriorityRequest.Runs request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.undo(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/runs/restore")
    @PreAuthorize(VIEW)
    public CadPriorityReport restore(@PathVariable UUID storeId,
            @Valid @RequestBody CadPriorityRequest.Runs request) {
        return service.restore(storeId, request.ids());
    }
}
