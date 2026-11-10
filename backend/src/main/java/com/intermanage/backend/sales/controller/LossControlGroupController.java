package com.intermanage.backend.sales.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.LossControlArticlesRequest;
import com.intermanage.backend.sales.dto.LossControlGroupRequest;
import com.intermanage.backend.sales.dto.LossControlGroupResponse;
import com.intermanage.backend.sales.service.LossControlGroupService;

import jakarta.validation.Valid;

/** Dostawcy do kontroli strat w raporcie protokołów: grupy artykułów z kolorem. Każda zmiana zwraca całą listę. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/protocols/control-groups")
public class LossControlGroupController {

    private static final String CAN_EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final LossControlGroupService service;

    public LossControlGroupController(LossControlGroupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<LossControlGroupResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PostMapping
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> create(@PathVariable UUID storeId,
            @Valid @RequestBody LossControlGroupRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> update(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LossControlGroupRequest request) {
        return service.update(storeId, id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }

    @PostMapping("/{id}/articles")
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> addArticles(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LossControlArticlesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.addArticles(storeId, id, request.itm8s(), principal.getId());
    }

    /** Odpięcie to znacznik; „Przywróć” to ponowne podpięcie. POST, bo ITM8 może być dużo. */
    @PostMapping("/{id}/articles/remove")
    @PreAuthorize(CAN_EDIT)
    public List<LossControlGroupResponse> removeArticles(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LossControlArticlesRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.removeArticles(storeId, id, request.itm8s(), principal.getId());
    }
}
