package com.intermanage.backend.collector.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.collector.dto.StorePrinterRequest;
import com.intermanage.backend.collector.dto.StorePrinterResponse;
import com.intermanage.backend.collector.service.StorePrinterService;

/** Drukarki Bluetooth sklepu dla kolektorów (Kolektory, Urządzenia), jak same kolektory {@code INVENTORY:EDIT}. */
@RestController
@RequestMapping("/api/stores/{storeId}/printers")
@PreAuthorize("hasAuthority('INVENTORY:EDIT') and " + Access.STORE)
public class StorePrinterController {

    private final StorePrinterService service;

    public StorePrinterController(StorePrinterService service) {
        this.service = service;
    }

    @GetMapping
    public List<StorePrinterResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    @PostMapping
    public List<StorePrinterResponse> add(@PathVariable UUID storeId, @RequestBody StorePrinterRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.add(storeId, request, principal.getId());
    }

    @PutMapping("/{printerId}")
    public List<StorePrinterResponse> change(@PathVariable UUID storeId, @PathVariable UUID printerId,
            @RequestBody StorePrinterRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.change(storeId, printerId, request, principal.getId());
    }

    @DeleteMapping("/{printerId}")
    public List<StorePrinterResponse> remove(@PathVariable UUID storeId, @PathVariable UUID printerId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, printerId, principal.getId());
    }

    @PostMapping("/{printerId}/restore")
    public List<StorePrinterResponse> restore(@PathVariable UUID storeId, @PathVariable UUID printerId) {
        return service.restore(storeId, printerId);
    }
}
