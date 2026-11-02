package com.intermanage.backend.deliproduction.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.deliproduction.dto.ProductionCardSummary;
import com.intermanage.backend.deliproduction.service.ProductionCardService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Karty produkcji przy ruchu międzymagazynowym (MM): przypinanie w sklepie wysyłającym i podgląd z wydrukiem
 * w sklepie docelowym przy dokumencie do wprowadzenia. Uprawnienia jak przy dokumentach.
 */
@RestController
@RequestMapping("/api/stores/{storeId}")
public class DocumentProductionCardController {

    private static final String STORE = " and @storeAccess.canAccess(authentication, #storeId)";
    private static final String VIEW = "hasAuthority('DOCUMENTS:VIEW')" + STORE;
    private static final String EDIT = "hasAuthority('DOCUMENTS:EDIT')" + STORE;

    public record LinkRequest(@NotNull(message = "Wybierz kartę") UUID cardId) {
    }

    private final ProductionCardService cards;

    public DocumentProductionCardController(ProductionCardService cards) {
        this.cards = cards;
    }

    @GetMapping("/documents/{type}/{id}/production-cards")
    @PreAuthorize(VIEW)
    public List<ProductionCardSummary> linked(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id) {
        return cards.documentCards(storeId, type, id);
    }

    @GetMapping("/documents/{type}/{id}/production-cards/candidates")
    @PreAuthorize(EDIT)
    public List<ProductionCardSummary> candidates(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id) {
        return cards.candidates(storeId, type, id);
    }

    @PostMapping("/documents/{type}/{id}/production-cards")
    @PreAuthorize(EDIT)
    public List<ProductionCardSummary> link(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @Valid @RequestBody LinkRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return cards.link(storeId, type, id, request.cardId(), principal.getId());
    }

    /** Odpięcie (znacznik); "Przywróć" to ponowne przypięcie. */
    @DeleteMapping("/documents/{type}/{id}/production-cards/{cardId}")
    @PreAuthorize(EDIT)
    public List<ProductionCardSummary> unlink(@PathVariable UUID storeId, @PathVariable String type,
            @PathVariable UUID id, @PathVariable UUID cardId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return cards.unlink(storeId, type, id, cardId, principal.getId());
    }

    @GetMapping("/documents/{type}/{id}/production-cards/{cardId}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID storeId, @PathVariable String type, @PathVariable UUID id,
            @PathVariable UUID cardId) {
        return ProductionController.pdf(cards.documentCardPdf(storeId, type, id, cardId));
    }

    @GetMapping("/incoming-transfers/{id}/production-cards")
    @PreAuthorize(VIEW)
    public List<ProductionCardSummary> incoming(@PathVariable UUID storeId, @PathVariable UUID id) {
        return cards.incomingCards(storeId, id);
    }

    @GetMapping("/incoming-transfers/{id}/production-cards/{cardId}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> incomingPdf(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID cardId) {
        return ProductionController.pdf(cards.incomingCardPdf(storeId, id, cardId));
    }
}
