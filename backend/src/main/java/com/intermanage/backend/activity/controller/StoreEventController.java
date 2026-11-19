package com.intermanage.backend.activity.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.activity.dto.StoreEventFilters;
import com.intermanage.backend.activity.dto.StoreEventPage;
import com.intermanage.backend.activity.entity.StoreEventKind;
import com.intermanage.backend.activity.service.StoreEventService;

/**
 * Dziennik sklepu dla kierowników ({@code STORE_LOG:VIEW}): zmiany wszystkich osób w sklepie i problemy systemu.
 * Dziennik firmy (ustawienia, zadania w tle bez sklepu) widzi tylko właściciel, pod {@code /api/settings}.
 */
@RestController
public class StoreEventController {

    private final StoreEventService service;

    public StoreEventController(StoreEventService service) {
        this.service = service;
    }

    @GetMapping("/api/stores/{storeId}/events")
    @PreAuthorize("hasAuthority('STORE_LOG:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public StoreEventPage store(@PathVariable UUID storeId, @RequestParam(required = false) Instant beforeAt,
            @RequestParam(required = false) UUID beforeId, @RequestParam(required = false) StoreEventKind kind,
            @RequestParam(required = false) String module, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String query) {
        return service.page(storeId,
                new StoreEventService.Query(beforeAt, beforeId, kind, module, userId, from, to, query));
    }

    @GetMapping("/api/stores/{storeId}/events/filters")
    @PreAuthorize("hasAuthority('STORE_LOG:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public StoreEventFilters storeFilters(@PathVariable UUID storeId) {
        return service.filters(storeId);
    }

    @GetMapping("/api/settings/events")
    public StoreEventPage company(@RequestParam(required = false) Instant beforeAt,
            @RequestParam(required = false) UUID beforeId, @RequestParam(required = false) StoreEventKind kind,
            @RequestParam(required = false) String module, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String query) {
        return service.page(null, new StoreEventService.Query(beforeAt, beforeId, kind, module, userId, from, to,
                query));
    }

    @GetMapping("/api/settings/events/filters")
    public StoreEventFilters companyFilters() {
        return service.filters(null);
    }
}
