package com.intermanage.backend.sales.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.MissingAssortmentAssignRequest;
import com.intermanage.backend.sales.dto.MissingAssortmentCategoryRequest;
import com.intermanage.backend.sales.dto.MissingAssortmentCategoryResponse;
import com.intermanage.backend.sales.dto.MissingAssortmentReport;
import com.intermanage.backend.sales.dto.MissingAssortmentRequest;
import com.intermanage.backend.sales.service.MissingAssortmentService;

import jakarta.validation.Valid;

/** Raport brakującego asortymentu i jego kategorie; każda zmiana kategorii zwraca kategorie widoczne w sklepie. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/missing-assortment")
public class MissingAssortmentController {

    private static final String CAN_VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String CAN_EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final MissingAssortmentService service;

    public MissingAssortmentController(MissingAssortmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(CAN_VIEW)
    public MissingAssortmentReport report(@PathVariable UUID storeId,
            @ModelAttribute MissingAssortmentRequest request) {
        return service.report(storeId, request);
    }

    @GetMapping("/categories")
    @PreAuthorize(CAN_VIEW)
    public List<MissingAssortmentCategoryResponse> categories(@PathVariable UUID storeId) {
        return service.categories(storeId);
    }

    @PostMapping("/categories")
    @PreAuthorize(CAN_EDIT)
    public List<MissingAssortmentCategoryResponse> create(@PathVariable UUID storeId,
            @Valid @RequestBody MissingAssortmentCategoryRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize(CAN_EDIT)
    public List<MissingAssortmentCategoryResponse> rename(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody MissingAssortmentCategoryRequest request) {
        return service.rename(storeId, id, request);
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize(CAN_EDIT)
    public List<MissingAssortmentCategoryResponse> delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/categories/{id}/restore")
    @PreAuthorize(CAN_EDIT)
    public List<MissingAssortmentCategoryResponse> restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }

    /** Przypisanie artykułów do kategorii (pusta = bez kategorii); „Cofnij” to ten sam endpoint z poprzednimi. */
    @PutMapping("/assignments")
    @PreAuthorize(CAN_EDIT)
    public List<MissingAssortmentCategoryResponse> assign(@PathVariable UUID storeId,
            @Valid @RequestBody MissingAssortmentAssignRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.assign(storeId, request, principal.getId());
    }
}
