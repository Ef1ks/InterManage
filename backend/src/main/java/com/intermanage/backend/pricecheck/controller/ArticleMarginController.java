package com.intermanage.backend.pricecheck.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.pricecheck.dto.ArticleMarginReport;
import com.intermanage.backend.pricecheck.dto.ArticleMarginVerificationIdsRequest;
import com.intermanage.backend.pricecheck.dto.ArticleMarginVerificationRequest;
import com.intermanage.backend.pricecheck.service.ArticleMarginService;

import jakarta.validation.Valid;

/** Marże całej kartoteki z aktualnych cen: artykuły z za niską marżą i aktualne promocje (bez gazetki). */
@RestController
@RequestMapping("/api/stores/{storeId}/reports")
public class ArticleMarginController {

    private final ArticleMarginService service;

    public ArticleMarginController(ArticleMarginService service) {
        this.service = service;
    }

    /** Artykuły z marżą poniżej {@code maxMargin} % (domyślnie 20%). */
    @GetMapping("/article-margins")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ArticleMarginReport lowMargins(@PathVariable UUID storeId,
            @RequestParam(required = false) BigDecimal maxMargin) {
        return service.lowMargins(storeId, maxMargin);
    }

    /** Oznacza artykuły jako sprawdzone przy podanych cenach; zwraca id wpisów do „Cofnij”. */
    @PostMapping("/article-margins/verifications")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    public List<UUID> verify(@PathVariable UUID storeId, @Valid @RequestBody ArticleMarginVerificationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.verify(storeId, request, principal.getId());
    }

    /** Cofa weryfikacje (znacznik). */
    @PostMapping("/article-margins/verifications/cancel")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID storeId, @Valid @RequestBody ArticleMarginVerificationIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.cancel(storeId, request.ids(), principal.getId());
    }

    /** Przywraca cofnięte weryfikacje. */
    @PostMapping("/article-margins/verifications/restore")
    @PreAuthorize("hasAuthority('SALES:EDIT') and @storeAccess.canAccess(authentication, #storeId)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID storeId, @Valid @RequestBody ArticleMarginVerificationIdsRequest request) {
        service.restore(storeId, request.ids());
    }

    /** Promocje trwające dziś z marżą na cenie promocyjnej, bez artykułów z gazetki. */
    @GetMapping("/current-promotions")
    @PreAuthorize("hasAuthority('SALES:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public ArticleMarginReport currentPromotions(@PathVariable UUID storeId) {
        return service.currentPromotions(storeId);
    }
}
