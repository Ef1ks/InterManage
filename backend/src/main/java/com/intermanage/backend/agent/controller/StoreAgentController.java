package com.intermanage.backend.agent.controller;

import java.util.Map;
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

import com.intermanage.backend.agent.dto.AgentStatusResponse;
import com.intermanage.backend.agent.dto.StoreAgentsResponse;
import com.intermanage.backend.agent.service.StoreAgentService;
import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;

/**
 * Agenci sklepu w Ustawieniach sklepu: na start tylko właściciel (klucz daje zapis plików w folderach sklepu).
 * Stan agenta ({@code /status}) widzi każdy z dostępem do sklepu, bo od niego zależy, którędy idzie zapis.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/agents")
public class StoreAgentController {

    private static final String OWNER = Access.STORE_ADMIN;

    private final StoreAgentService service;

    public StoreAgentController(StoreAgentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(OWNER)
    public StoreAgentsResponse list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Nowy agent: klucz jest w odpowiedzi tylko ten jeden raz. */
    @PostMapping
    @PreAuthorize(OWNER)
    public StoreAgentsResponse add(@PathVariable UUID storeId, @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.add(storeId, body.get("name"), principal.getId());
    }

    @DeleteMapping("/{agentId}")
    @PreAuthorize(OWNER)
    public StoreAgentsResponse remove(@PathVariable UUID storeId, @PathVariable UUID agentId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, agentId, principal.getId());
    }

    @PostMapping("/{agentId}/restore")
    @PreAuthorize(OWNER)
    public StoreAgentsResponse restore(@PathVariable UUID storeId, @PathVariable UUID agentId) {
        return service.restore(storeId, agentId);
    }

    /** Praca z folderami przez agenta ({@code true}) albo jak dotąd przez przeglądarkę. */
    @PutMapping("/settings")
    @PreAuthorize(OWNER)
    public StoreAgentsResponse settings(@PathVariable UUID storeId, @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setEnabled(storeId, Boolean.TRUE.equals(body.get("enabled")), principal.getId());
    }

    @GetMapping("/status")
    @PreAuthorize(Access.STORE)
    public AgentStatusResponse status(@PathVariable UUID storeId) {
        return service.status(storeId);
    }
}
