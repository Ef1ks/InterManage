package com.intermanage.backend.product.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.product.dto.ArticleLinkRequest;
import com.intermanage.backend.product.dto.ArticleLinkResponse;
import com.intermanage.backend.product.service.ArticleStoreLinkService;

import jakarta.validation.Valid;

/**
 * Połączenia tego samego towaru w różnych sklepach, wspólne dla raportu lady ({@code SALES:EDIT}) i porównania cen
 * mięsa ({@code MEAT_PURCHASES:EDIT}). Raporty same czytają połączenia, więc tu są tylko zmiany.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/article-links")
@PreAuthorize("(hasAuthority('SALES:EDIT') or hasAuthority('MEAT_PURCHASES:EDIT'))"
        + " and @storeAccess.canAccess(authentication, #storeId)")
public class ArticleStoreLinkController {

    private final ArticleStoreLinkService service;

    public ArticleStoreLinkController(ArticleStoreLinkService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleLinkResponse link(@PathVariable UUID storeId, @Valid @RequestBody ArticleLinkRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.link(storeId, principal.getId(), request);
    }

    /** Odłączenie to znacznik; „Przywróć” = {@code POST .../{id}/restore}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.unlink(storeId, principal.getId(), id);
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.restore(storeId, principal.getId(), id);
    }
}
