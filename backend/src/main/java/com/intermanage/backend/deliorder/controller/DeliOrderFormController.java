package com.intermanage.backend.deliorder.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.deliorder.dto.AddOrderProductRequest;
import com.intermanage.backend.deliorder.dto.OrderFormResponse;
import com.intermanage.backend.deliorder.dto.OrderNameRequest;
import com.intermanage.backend.deliorder.dto.PasteOrderProductsRequest;
import com.intermanage.backend.deliorder.dto.PasteOrderProductsResponse;
import com.intermanage.backend.deliorder.dto.UpdateOrderUnitRequest;
import com.intermanage.backend.deliorder.service.DeliOrderFormService;
import com.intermanage.backend.product.dto.ProductSearchResponse;

import jakarta.validation.Valid;

/** Formularz zamówienia garmażu sklepu. Podgląd: DELI_ORDERS:VIEW, układanie formularza: DELI_ORDERS:EDIT. */
@RestController
@RequestMapping("/api/stores/{storeId}/deli-order")
public class DeliOrderFormController {

    private static final String VIEW =
            "hasAuthority('DELI_ORDERS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('DELI_ORDERS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final DeliOrderFormService formService;

    public DeliOrderFormController(DeliOrderFormService formService) {
        this.formService = formService;
    }

    @GetMapping("/form")
    @PreAuthorize(VIEW)
    public OrderFormResponse form(@PathVariable UUID storeId) {
        return formService.form(storeId);
    }

    @PostMapping("/sections")
    @PreAuthorize(EDIT)
    public OrderFormResponse createSection(@PathVariable UUID storeId, @Valid @RequestBody OrderNameRequest request) {
        return formService.createSection(storeId, request.name());
    }

    @PutMapping("/sections/{sectionId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse renameSection(@PathVariable UUID storeId, @PathVariable UUID sectionId,
            @Valid @RequestBody OrderNameRequest request) {
        return formService.renameSection(storeId, sectionId, request.name());
    }

    @DeleteMapping("/sections/{sectionId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse deleteSection(@PathVariable UUID storeId, @PathVariable UUID sectionId) {
        return formService.deleteSection(storeId, sectionId);
    }

    @PostMapping("/sections/{sectionId}/categories")
    @PreAuthorize(EDIT)
    public OrderFormResponse createCategory(@PathVariable UUID storeId, @PathVariable UUID sectionId,
            @Valid @RequestBody OrderNameRequest request) {
        return formService.createCategory(storeId, sectionId, request.name());
    }

    @PutMapping("/categories/{categoryId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse renameCategory(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @Valid @RequestBody OrderNameRequest request) {
        return formService.renameCategory(storeId, categoryId, request.name());
    }

    @DeleteMapping("/categories/{categoryId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse deleteCategory(@PathVariable UUID storeId, @PathVariable UUID categoryId) {
        return formService.deleteCategory(storeId, categoryId);
    }

    @PostMapping("/categories/{categoryId}/products")
    @PreAuthorize(EDIT)
    public OrderFormResponse addProduct(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @Valid @RequestBody AddOrderProductRequest request) {
        return formService.addProduct(storeId, categoryId, request.productId());
    }

    /** Lista produktów wklejona z Excela (EAN + jednostka); znanym produktom zmienia jednostkę. */
    @PostMapping("/categories/{categoryId}/products/paste")
    @PreAuthorize(EDIT)
    public PasteOrderProductsResponse pasteProducts(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @Valid @RequestBody PasteOrderProductsRequest request) {
        return formService.pasteProducts(storeId, categoryId, request.rows());
    }

    @PutMapping("/categories/{categoryId}/products/{itemId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse setOrderUnit(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @PathVariable UUID itemId, @Valid @RequestBody UpdateOrderUnitRequest request) {
        return formService.setOrderUnit(storeId, categoryId, itemId, request.orderUnit());
    }

    @DeleteMapping("/categories/{categoryId}/products/{itemId}")
    @PreAuthorize(EDIT)
    public OrderFormResponse removeProduct(@PathVariable UUID storeId, @PathVariable UUID categoryId,
            @PathVariable UUID itemId) {
        return formService.removeProduct(storeId, categoryId, itemId);
    }

    /** Wyszukiwarka produktów kartoteki sklepu do dodawania w trybie edycji; od 3 znaków. */
    @GetMapping("/products")
    @PreAuthorize(EDIT)
    public List<ProductSearchResponse> searchProducts(@PathVariable UUID storeId, @RequestParam String query) {
        return formService.searchProducts(storeId, query);
    }
}
