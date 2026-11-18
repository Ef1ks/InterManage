package com.intermanage.backend.dataentry.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.dataentry.dto.InvoiceClosingIdsRequest;
import com.intermanage.backend.dataentry.dto.PromoPriceAddRequest;
import com.intermanage.backend.dataentry.dto.PromoPriceItem;
import com.intermanage.backend.dataentry.service.DataEntryPromoPriceService;

import jakarta.validation.Valid;

/** Okno „Ceny artykułów w promocji”: kolejka do makra taryf; każda zmiana zwraca listę. */
@RestController
@RequestMapping("/api/stores/{storeId}/data-entry/promo-prices")
public class DataEntryPromoPriceController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final DataEntryPromoPriceService prices;

    public DataEntryPromoPriceController(DataEntryPromoPriceService prices) {
        this.prices = prices;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<PromoPriceItem> list(@PathVariable UUID storeId) {
        return prices.list(storeId);
    }

    @PostMapping
    @PreAuthorize(VIEW)
    public List<PromoPriceItem> add(@PathVariable UUID storeId, @Valid @RequestBody PromoPriceAddRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return prices.add(storeId, request, principal.getId());
    }

    @PostMapping("/generated")
    @PreAuthorize(VIEW)
    public List<PromoPriceItem> generated(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return prices.markGenerated(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/delete")
    @PreAuthorize(VIEW)
    public List<PromoPriceItem> delete(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return prices.delete(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/restore")
    @PreAuthorize(VIEW)
    public List<PromoPriceItem> restore(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request) {
        return prices.restore(storeId, request.ids());
    }
}
