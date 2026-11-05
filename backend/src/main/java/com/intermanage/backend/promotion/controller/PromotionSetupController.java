package com.intermanage.backend.promotion.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.promotion.dto.PromotionSetupArticle;
import com.intermanage.backend.promotion.service.PromotionSetupService;

/** Ustawianie promocji (uprawnienie jak wprowadzanie danych). */
@RestController
@RequestMapping("/api/stores/{storeId}/promotion-setup")
@PreAuthorize("hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
public class PromotionSetupController {

    private final PromotionSetupService service;

    public PromotionSetupController(PromotionSetupService service) {
        this.service = service;
    }

    /** Artykuły dla wpisanych kodów ({@code ?code=…&code=…}). */
    @GetMapping("/articles")
    public List<PromotionSetupArticle> articles(@PathVariable UUID storeId, @RequestParam List<String> code) {
        return service.articles(storeId, code);
    }
}
