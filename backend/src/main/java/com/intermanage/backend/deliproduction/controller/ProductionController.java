package com.intermanage.backend.deliproduction.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.deliproduction.dto.ArticleSchemaRequest;
import com.intermanage.backend.deliproduction.dto.ProductionArticleRequest;
import com.intermanage.backend.deliproduction.dto.ProductionBaseResponse;
import com.intermanage.backend.deliproduction.dto.ProductionCardRequest;
import com.intermanage.backend.deliproduction.dto.ProductionCardResponse;
import com.intermanage.backend.deliproduction.dto.ProductionCardSummary;
import com.intermanage.backend.deliproduction.dto.ProductionImportResponse;
import com.intermanage.backend.deliproduction.dto.ProductionSchemaRequest;
import com.intermanage.backend.deliproduction.dto.ProductionSettingsRequest;
import com.intermanage.backend.deliproduction.service.ProductionBaseService;
import com.intermanage.backend.deliproduction.service.ProductionCardService;
import com.intermanage.backend.document.service.DocumentService;

import jakarta.validation.Valid;

/**
 * Karty produkcji garmażu: baza artykułów ze schematami parametrów, formularz do wydruku, karty sklepu i sklepów
 * zaprzyjaźnionych (tylko podgląd i wydruk).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/production")
public class ProductionController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAuthority('DELI_PRODUCTION:VIEW')" + STORE;
    private static final String EDIT = "hasAuthority('DELI_PRODUCTION:EDIT')" + STORE;

    private final ProductionBaseService base;
    private final ProductionCardService cards;

    public ProductionController(ProductionBaseService base, ProductionCardService cards) {
        this.base = base;
        this.cards = cards;
    }

    @GetMapping("/base")
    @PreAuthorize(VIEW)
    public ProductionBaseResponse base(@PathVariable UUID storeId) {
        return base.base(storeId);
    }

    @PostMapping("/base/import")
    @PreAuthorize(EDIT)
    public ProductionImportResponse importBase(@PathVariable UUID storeId, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.importBase(storeId, file, principal.getId());
    }

    @PutMapping("/settings")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse saveSettings(@PathVariable UUID storeId,
            @Valid @RequestBody ProductionSettingsRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.saveStamp(storeId, request.stamp(), principal.getId());
    }

    @PostMapping("/schemas")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse createSchema(@PathVariable UUID storeId,
            @Valid @RequestBody ProductionSchemaRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.createSchema(storeId, request, principal.getId());
    }

    @PutMapping("/schemas/{schemaId}")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse updateSchema(@PathVariable UUID storeId, @PathVariable UUID schemaId,
            @Valid @RequestBody ProductionSchemaRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.updateSchema(storeId, schemaId, request, principal.getId());
    }

    /** Usunięcie (znacznik); "Przywróć" to {@code POST …/{schemaId}/restore}. */
    @DeleteMapping("/schemas/{schemaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void deleteSchema(@PathVariable UUID storeId, @PathVariable UUID schemaId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        base.deleteSchema(storeId, schemaId, principal.getId());
    }

    @PostMapping("/schemas/{schemaId}/restore")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse restoreSchema(@PathVariable UUID storeId, @PathVariable UUID schemaId) {
        return base.restoreSchema(storeId, schemaId);
    }

    @PostMapping("/articles")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse createArticle(@PathVariable UUID storeId,
            @Valid @RequestBody ProductionArticleRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.createArticle(storeId, request, principal.getId());
    }

    /** Ten sam schemat dla zaznaczonych artykułów. */
    @PutMapping("/articles/schema")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse changeSchema(@PathVariable UUID storeId,
            @Valid @RequestBody ArticleSchemaRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.changeSchema(storeId, request, principal.getId());
    }

    @PutMapping("/articles/{articleId}")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse updateArticle(@PathVariable UUID storeId, @PathVariable UUID articleId,
            @Valid @RequestBody ProductionArticleRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return base.updateArticle(storeId, articleId, request, principal.getId());
    }

    @DeleteMapping("/articles/{articleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void deleteArticle(@PathVariable UUID storeId, @PathVariable UUID articleId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        base.deleteArticle(storeId, articleId, principal.getId());
    }

    @PostMapping("/articles/{articleId}/restore")
    @PreAuthorize(EDIT)
    public ProductionBaseResponse restoreArticle(@PathVariable UUID storeId, @PathVariable UUID articleId) {
        return base.restoreArticle(storeId, articleId);
    }

    /** Formularz do uzupełnienia długopisem: artykuły bazy alfabetycznie, dwie tabele na kartce A4. */
    @GetMapping("/form/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> formPdf(@PathVariable UUID storeId) {
        return pdf(new DocumentService.ExportFile("formularz-karty-produkcji.pdf", "application/pdf",
                base.formPdf(storeId)));
    }

    /** {@code scope=PARTNERS}: karty sklepów zaprzyjaźnionych. */
    @GetMapping("/cards")
    @PreAuthorize(VIEW)
    public List<ProductionCardSummary> cards(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "OWN") ProductionCardService.Scope scope) {
        return cards.list(storeId, from, to, scope);
    }

    @GetMapping("/cards/{cardId}")
    @PreAuthorize(VIEW)
    public ProductionCardResponse card(@PathVariable UUID storeId, @PathVariable UUID cardId) {
        return cards.get(storeId, cardId);
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public ProductionCardResponse create(@PathVariable UUID storeId, @Valid @RequestBody ProductionCardRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return cards.create(storeId, request, principal.getId());
    }

    @PutMapping("/cards/{cardId}")
    @PreAuthorize(EDIT)
    public ProductionCardResponse update(@PathVariable UUID storeId, @PathVariable UUID cardId,
            @Valid @RequestBody ProductionCardRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return cards.update(storeId, cardId, request, principal.getId());
    }

    @DeleteMapping("/cards/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID cardId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        cards.delete(storeId, cardId, principal.getId());
    }

    @PostMapping("/cards/{cardId}/restore")
    @PreAuthorize(EDIT)
    public ProductionCardResponse restore(@PathVariable UUID storeId, @PathVariable UUID cardId) {
        return cards.restore(storeId, cardId);
    }

    @GetMapping("/cards/{cardId}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> cardPdf(@PathVariable UUID storeId, @PathVariable UUID cardId) {
        return pdf(cards.pdf(storeId, cardId));
    }

    static ResponseEntity<byte[]> pdf(DocumentService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.content());
    }
}
