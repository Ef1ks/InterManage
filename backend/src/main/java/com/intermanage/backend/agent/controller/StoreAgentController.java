package com.intermanage.backend.agent.controller;

import java.util.List;
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

import com.intermanage.backend.agent.dto.AgentFeatureResponse;
import com.intermanage.backend.agent.dto.AgentStatusResponse;
import com.intermanage.backend.agent.dto.StoreAgentsResponse;
import com.intermanage.backend.agent.entity.AgentFeature;
import com.intermanage.backend.agent.service.StoreAgentFeatureService;
import com.intermanage.backend.agent.service.StoreAgentService;
import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;

/**
 * Agenci sklepu w Ustawieniach sklepu ({@code STORE_AGENT}): podgląd widzi listę i wybiera preferowanego agenta, edycja
 * dodaje i usuwa agentów (klucz daje zapis plików w folderach sklepu) i przełącza pracę przez agenta.
 * Stan agenta ({@code /status}) widzi każdy z dostępem do sklepu, bo od niego zależy, którędy idzie zapis.
 */
@RestController
@RequestMapping("/api/stores/{storeId}/agents")
public class StoreAgentController {

    private static final String VIEW = "hasAuthority('STORE_AGENT:VIEW') and " + Access.STORE;
    private static final String EDIT = "hasAuthority('STORE_AGENT:EDIT') and " + Access.STORE;
    private static final String FEATURES = "hasAuthority('DATA_ENTRY:EDIT') and " + Access.STORE;

    private final StoreAgentService service;
    private final StoreAgentFeatureService features;

    public StoreAgentController(StoreAgentService service, StoreAgentFeatureService features) {
        this.service = service;
        this.features = features;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public StoreAgentsResponse list(@PathVariable UUID storeId) {
        return service.list(storeId);
    }

    /** Nowy agent: klucz jest w odpowiedzi tylko ten jeden raz. */
    @PostMapping
    @PreAuthorize(EDIT)
    public StoreAgentsResponse add(@PathVariable UUID storeId, @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.add(storeId, body.get("name"), principal.getId());
    }

    @DeleteMapping("/{agentId}")
    @PreAuthorize(EDIT)
    public StoreAgentsResponse remove(@PathVariable UUID storeId, @PathVariable UUID agentId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.remove(storeId, agentId, principal.getId());
    }

    @PostMapping("/{agentId}/restore")
    @PreAuthorize(EDIT)
    public StoreAgentsResponse restore(@PathVariable UUID storeId, @PathVariable UUID agentId) {
        return service.restore(storeId, agentId);
    }

    /** Agent preferowany na głównego, najwyżej jeden w sklepie (pilnuje folderów i aktualizuje dane z Mercalys). */
    @PutMapping("/{agentId}/preferred")
    @PreAuthorize(VIEW)
    public StoreAgentsResponse preferred(@PathVariable UUID storeId, @PathVariable UUID agentId,
            @RequestBody Map<String, Boolean> body) {
        return service.setPreferred(storeId, agentId, Boolean.TRUE.equals(body.get("preferred")));
    }

    /** Praca z folderami przez agenta ({@code true}) albo jak dotąd przez przeglądarkę. */
    @PutMapping("/settings")
    @PreAuthorize(EDIT)
    public StoreAgentsResponse settings(@PathVariable UUID storeId, @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setEnabled(storeId, Boolean.TRUE.equals(body.get("enabled")), principal.getId());
    }

    /** Co agent robi w sklepie, każda funkcja osobno; przełącza każdy z edycją wprowadzania danych. */
    @GetMapping("/features")
    @PreAuthorize(FEATURES)
    public List<AgentFeatureResponse> features(@PathVariable UUID storeId) {
        return features.list(storeId);
    }

    @PutMapping("/features/{feature}")
    @PreAuthorize(FEATURES)
    public List<AgentFeatureResponse> feature(@PathVariable UUID storeId, @PathVariable AgentFeature feature,
            @RequestBody Map<String, Boolean> body, @AuthenticationPrincipal AppUserPrincipal principal) {
        return features.set(storeId, feature, Boolean.TRUE.equals(body.get("enabled")), principal.getId());
    }

    @GetMapping("/status")
    @PreAuthorize(Access.STORE)
    public AgentStatusResponse status(@PathVariable UUID storeId) {
        return service.status(storeId);
    }
}
