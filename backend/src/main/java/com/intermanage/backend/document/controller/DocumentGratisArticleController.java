package com.intermanage.backend.document.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.document.dto.GratisArticleItem;
import com.intermanage.backend.document.dto.GratisArticleRequest;
import com.intermanage.backend.document.service.DocumentGratisArticleService;

import jakarta.validation.Valid;

/**
 * Artykuły sklepu, które w przeniesieniach stanów oznaczają gratis. Ponowne dodanie po usunięciu ({@code POST}) to
 * „Przywróć” z toastu.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/documents/gratis-articles")
public class DocumentGratisArticleController {

    private static final String VIEW =
            "hasAuthority('DOCUMENTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DOCUMENTS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DocumentGratisArticleService articles;

    public DocumentGratisArticleController(DocumentGratisArticleService articles) {
        this.articles = articles;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<GratisArticleItem> list(@PathVariable UUID storeId) {
        return articles.list(storeId);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public GratisArticleItem add(@PathVariable UUID storeId, @Valid @RequestBody GratisArticleRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return articles.add(storeId, request.itm8(), principal.getId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        articles.remove(storeId, id, principal.getId());
    }
}
