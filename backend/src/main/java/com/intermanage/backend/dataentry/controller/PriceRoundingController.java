package com.intermanage.backend.dataentry.controller;

import java.util.List;
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
import com.intermanage.backend.dataentry.dto.PriceRoundingRequest;
import com.intermanage.backend.dataentry.dto.PriceRoundingRuleResponse;
import com.intermanage.backend.dataentry.service.PriceRoundingService;

import jakarta.validation.Valid;

/**
 * Przedziały zaokrąglania cen sugerowanych: ustawia je osoba z {@code SALES:EDIT} (Ustawienia sklepu, obok marży
 * wymaganej), a czyta też wprowadzanie danych.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/price-rounding")
public class PriceRoundingController {

    private static final String EDIT =
            "hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceRoundingService service;

    public PriceRoundingController(PriceRoundingService service) {
        this.service = service;
    }

    /** Słownik: ceny sugerowane liczą różne narzędzia, więc wystarczy dostęp do sklepu. */
    @GetMapping
    @PreAuthorize(Access.STORE)
    public List<PriceRoundingRuleResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PutMapping
    @PreAuthorize(EDIT)
    public List<PriceRoundingRuleResponse> save(@PathVariable UUID storeId, @Valid @RequestBody PriceRoundingRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.save(storeId, request, principal.getId());
    }
}
