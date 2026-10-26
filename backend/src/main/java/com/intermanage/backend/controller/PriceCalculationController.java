package com.intermanage.backend.controller;

import java.util.List;
import java.util.UUID;

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

import com.intermanage.backend.dto.FinishedProductResponse;
import com.intermanage.backend.dto.PriceCalculationRequest;
import com.intermanage.backend.dto.PriceCalculationResponse;
import com.intermanage.backend.dto.PriceCalculationSummary;
import com.intermanage.backend.dto.PriceChangeResponse;
import com.intermanage.backend.dto.PriceProductResponse;
import com.intermanage.backend.dto.RefreshPricesRequest;
import com.intermanage.backend.security.AppUserPrincipal;
import com.intermanage.backend.service.PriceCalculationService;

import jakarta.validation.Valid;

/** Kalkulator cen garmażu sklepu. Podgląd: DELI_PRICES:VIEW, tworzenie i zmiany kalkulacji: DELI_PRICES:EDIT. */
@RestController
@RequestMapping("/api/stores/{storeId}/price-calculations")
public class PriceCalculationController {

    private static final String VIEW =
            "hasAuthority('DELI_PRICES:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DELI_PRICES:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final PriceCalculationService service;

    public PriceCalculationController(PriceCalculationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<PriceCalculationSummary> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Gotowe wyroby z działów garmażu (nomenklatury 24, 36, 46) do wyboru w kreatorze. */
    @GetMapping("/finished-products")
    @PreAuthorize(VIEW)
    public List<FinishedProductResponse> finishedProducts(@PathVariable UUID storeId) {
        return service.finishedProducts(storeId);
    }

    /** Ceny sprzedaży wszystkich kalkulacji do Excela: EAN, nazwa, cena brutto, VAT. */
    @GetMapping("/export/xlsx")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable UUID storeId) {
        PriceCalculationService.ExportFile file = service.export(storeId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /**
     * Podgląd "Odśwież ceny" (bez zmian): nowe wyliczone ceny i które kalkulacje wymagają decyzji.
     * {@code calculationId} - jedna kalkulacja, bez niego wszystkie.
     */
    @PostMapping("/refresh-prices/preview")
    @PreAuthorize(EDIT)
    public List<PriceChangeResponse> previewRefresh(@PathVariable UUID storeId,
            @RequestParam(required = false) UUID calculationId) {
        return service.previewRefresh(storeId, calculationId);
    }

    /** "Odśwież ceny" z decyzjami z podglądu: składniki dostają ceny z ostatniej dostawy PZ, a bez niej z kartoteki. */
    @PostMapping("/refresh-prices")
    @PreAuthorize(EDIT)
    public List<PriceCalculationSummary> refreshPrices(@PathVariable UUID storeId,
            @Valid @RequestBody RefreshPricesRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.refresh(storeId, request, principal.getId());
    }

    /**
     * Składniki z kartoteki: od 3 znaków, słowa w dowolnej kolejności, jak w zamówieniu garmażu; z ceną z ostatniej
     * dostawy albo z kartoteki.
     */
    @GetMapping("/products")
    @PreAuthorize(VIEW)
    public List<PriceProductResponse> searchProducts(@PathVariable UUID storeId, @RequestParam String query) {
        return service.searchProducts(storeId, query);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public PriceCalculationResponse get(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.get(storeId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public PriceCalculationResponse create(@PathVariable UUID storeId, @Valid @RequestBody PriceCalculationRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.create(storeId, request, principal.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public PriceCalculationResponse update(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody PriceCalculationRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.update(storeId, id, request, principal.getId());
    }

    /** Usunięcie to znacznik; {@code POST .../restore} przywraca kalkulację. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        service.delete(storeId, id, principal.getId());
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(EDIT)
    public PriceCalculationResponse restore(@PathVariable UUID storeId, @PathVariable UUID id) {
        return service.restore(storeId, id);
    }
}
