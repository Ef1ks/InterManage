package com.intermanage.backend.sales.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.sales.dto.ProfitTargetProgressResponse;
import com.intermanage.backend.sales.dto.ProfitTargetRequest;
import com.intermanage.backend.sales.dto.ProfitTargetResponse;
import com.intermanage.backend.sales.service.ProfitTargetService;

import jakarta.validation.Valid;

/** Roczne cele zysku sklepu w ustawieniach podsumowania sprzedaży i strat i ich realizacja od początku roku. */
@RestController
@RequestMapping("/api/stores/{storeId}/reports/profit-targets")
public class ProfitTargetController {

    private static final String VIEW =
            "hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final ProfitTargetService service;

    public ProfitTargetController(ProfitTargetService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<ProfitTargetResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PutMapping("/{year}")
    @PreAuthorize(EDIT)
    public List<ProfitTargetResponse> save(@PathVariable UUID storeId, @PathVariable int year,
            @Valid @RequestBody ProfitTargetRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, year, request, principal.getId());
    }

    @GetMapping("/progress")
    @PreAuthorize(VIEW)
    public ProfitTargetProgressResponse progress(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.progress(storeId, to);
    }
}
