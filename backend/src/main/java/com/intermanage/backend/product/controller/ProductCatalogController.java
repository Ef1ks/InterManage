package com.intermanage.backend.product.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.product.dto.ProductListPage;
import com.intermanage.backend.product.dto.ProductListRequest;
import com.intermanage.backend.product.dto.ProductPriceHistoryResponse;
import com.intermanage.backend.product.service.ProductCatalogService;

/**
 * Magazyn i produkty: lista artykułów kartoteki i historia cen artykułu. Stan, sprzedaż i dostawy artykułu podaje
 * {@code SalesReportController} (też dla {@code INVENTORY:VIEW}).
 */
@RestController
@RequestMapping("/api/stores/{storeId}/products")
public class ProductCatalogController {

    private static final String VIEW = "hasAuthority('INVENTORY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final ProductCatalogService service;

    public ProductCatalogController(ProductCatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public ProductListPage list(@PathVariable UUID storeId, @ModelAttribute ProductListRequest request) {
        return service.list(storeId, request);
    }

    /** Też dla {@code SALES:VIEW}: historia cen jest w oknie statystyk artykułu z raportów sprzedaży. */
    @GetMapping("/{itm8}/prices")
    @PreAuthorize("(hasAuthority('INVENTORY:VIEW') or hasAuthority('SALES:VIEW')) and @storeAccess.canAccess(authentication, #storeId)")
    public ProductPriceHistoryResponse prices(@PathVariable UUID storeId, @PathVariable String itm8) {
        return service.priceHistory(storeId, itm8);
    }
}
