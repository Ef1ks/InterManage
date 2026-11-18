package com.intermanage.backend.ai.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.ai.dto.AiUsageReport;
import com.intermanage.backend.ai.service.AiUsageService;

/** Zużycie tokenów AI sklepu (Ustawienia sklepu): dla tych, którzy mogą wołać model. */
@RestController
@RequestMapping("/api/stores/{storeId}/ai-usage")
public class AiUsageController {

    private final AiUsageService service;

    public AiUsageController(AiUsageService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("(hasAuthority('DATA_ENTRY:EDIT') or hasAuthority('MEAT_PURCHASES:EDIT'))"
            + " and @storeAccess.canAccess(authentication, #storeId)")
    public AiUsageReport report(@PathVariable UUID storeId, @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return service.report(storeId, from, to);
    }
}
