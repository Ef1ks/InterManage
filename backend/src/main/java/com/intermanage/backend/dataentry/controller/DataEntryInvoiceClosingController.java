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
import com.intermanage.backend.dataentry.dto.InvoiceClosingItem;
import com.intermanage.backend.dataentry.service.DataEntryInvoiceClosingService;

import jakarta.validation.Valid;

/** Okno "Zamknij faktury": kolejka faktur z zapisanych plików XML; każda zmiana zwraca listę. */
@RestController
@RequestMapping("/api/stores/{storeId}/data-entry/invoice-closings")
public class DataEntryInvoiceClosingController {

    private static final String VIEW =
            "hasAuthority('DATA_ENTRY:VIEW') and @storeAccess.canAccess(authentication, #storeId)";

    private final DataEntryInvoiceClosingService closings;

    public DataEntryInvoiceClosingController(DataEntryInvoiceClosingService closings) {
        this.closings = closings;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<InvoiceClosingItem> list(@PathVariable UUID storeId) {
        return closings.list(storeId);
    }

    @PostMapping("/generated")
    @PreAuthorize(VIEW)
    public List<InvoiceClosingItem> generated(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return closings.markGenerated(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/delete")
    @PreAuthorize(VIEW)
    public List<InvoiceClosingItem> delete(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return closings.delete(storeId, request.ids(), principal.getId());
    }

    @PostMapping("/restore")
    @PreAuthorize(VIEW)
    public List<InvoiceClosingItem> restore(@PathVariable UUID storeId,
            @Valid @RequestBody InvoiceClosingIdsRequest request) {
        return closings.restore(storeId, request.ids());
    }
}
