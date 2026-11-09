package com.intermanage.backend.product.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.dataentry.component.LabelFile;
import com.intermanage.backend.dataentry.service.DataEntryProtocolService.ProtocolFile;
import com.intermanage.backend.product.dto.PriceMarkdownIdsRequest;
import com.intermanage.backend.product.dto.PriceMarkdownItem;
import com.intermanage.backend.product.dto.PriceMarkdownRequest;
import com.intermanage.backend.product.service.PriceMarkdownService;
import com.intermanage.backend.promotion.service.PromotionSetupService;
import com.intermanage.backend.promotion.service.PromotionSetupService.MarkdownPrices;

import jakarta.validation.Valid;

/** Przeceny z karty artykułu i lista cenówek; każda zmiana zwraca listę. */
@RestController
@RequestMapping("/api/stores/{storeId}/price-markdowns")
public class PriceMarkdownController {

    private static final String VIEW =
            "hasAuthority('INVENTORY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('INVENTORY:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceMarkdownService markdowns;

    private final PromotionSetupService promotionSetup;

    public PriceMarkdownController(PriceMarkdownService markdowns, PromotionSetupService promotionSetup) {
        this.markdowns = markdowns;
        this.promotionSetup = promotionSetup;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<PriceMarkdownItem> list(@PathVariable UUID storeId) {
        return markdowns.list(storeId);
    }

    /** Cena bez promocji i ostatnia dostawa artykułu do okna przeceny (jak w ustawianiu promocji); 404 spoza kartoteki. */
    @GetMapping("/prices")
    @PreAuthorize(VIEW)
    public ResponseEntity<MarkdownPrices> prices(@PathVariable UUID storeId, @RequestParam String itm8) {
        MarkdownPrices prices = promotionSetup.markdownPrices(storeId, itm8);
        return prices == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(prices);
    }

    @PostMapping
    @PreAuthorize(EDIT)
    public List<PriceMarkdownItem> create(@PathVariable UUID storeId, @Valid @RequestBody PriceMarkdownRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return markdowns.create(storeId, request, principal.getId());
    }

    /** Plik ETIQUETTE; zapisuje go przeglądarka w folderze plików PROTO i dopiero wtedy woła {@code labels-generated}. */
    @PostMapping("/labels-file")
    @PreAuthorize(EDIT)
    public ResponseEntity<byte[]> labelsFile(@PathVariable UUID storeId,
            @Valid @RequestBody PriceMarkdownIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ProtocolFile file = markdowns.labels(storeId, request.ids(), principal.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(LabelFile.CONTENT_TYPE))
                .body(file.content());
    }

    @PostMapping("/labels-generated")
    @PreAuthorize(EDIT)
    public List<PriceMarkdownItem> labelsGenerated(@PathVariable UUID storeId,
            @Valid @RequestBody PriceMarkdownIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return markdowns.markLabelsGenerated(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/delete")
    @PreAuthorize(EDIT)
    public List<PriceMarkdownItem> delete(@PathVariable UUID storeId,
            @Valid @RequestBody PriceMarkdownIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return markdowns.delete(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/restore")
    @PreAuthorize(EDIT)
    public List<PriceMarkdownItem> restore(@PathVariable UUID storeId,
            @Valid @RequestBody PriceMarkdownIdsRequest request) {
        return markdowns.restore(storeId, request.ids());
    }
}
