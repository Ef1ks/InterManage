package com.intermanage.backend.collector.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.collector.dto.StoreCollectorResponse;
import com.intermanage.backend.collector.service.StoreCollectorService;

/** Kolektory sklepu: kod parowania daje dostęp do kartoteki, więc lista i zmiany to {@code INVENTORY:EDIT}. */
@RestController
@RequestMapping("/api/stores/{storeId}/collectors")
@PreAuthorize("hasAuthority('INVENTORY:EDIT') and " + Access.STORE)
public class StoreCollectorController {

    private final StoreCollectorService service;

    public StoreCollectorController(StoreCollectorService service) {
        this.service = service;
    }

    @GetMapping
    public List<StoreCollectorResponse> list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Nowy kolektor z kodem parowania, który jest w odpowiedzi tylko ten jeden raz. */
    @PostMapping
    public List<StoreCollectorResponse> add(@PathVariable UUID storeId, @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.add(storeId, body.get("name"), principal.getId());
    }

    @PostMapping("/{collectorId}/code")
    public List<StoreCollectorResponse> newCode(@PathVariable UUID storeId, @PathVariable UUID collectorId) {
        return service.newCode(storeId, collectorId);
    }

    @DeleteMapping("/{collectorId}")
    public List<StoreCollectorResponse> remove(@PathVariable UUID storeId, @PathVariable UUID collectorId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, collectorId, principal.getId());
    }

    @PostMapping("/{collectorId}/restore")
    public List<StoreCollectorResponse> restore(@PathVariable UUID storeId, @PathVariable UUID collectorId) {
        return service.restore(storeId, collectorId);
    }
}
