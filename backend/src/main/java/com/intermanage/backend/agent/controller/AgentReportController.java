package com.intermanage.backend.agent.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.agent.dto.AgentReportFileResponse;
import com.intermanage.backend.agent.service.AgentReportService;

/** Raporty wgrane przez agenta sklepu, w Imporcie danych: ostatnie próby z wynikiem. */
@RestController
@RequestMapping("/api/stores/{storeId}/imports/agent-files")
public class AgentReportController {

    private static final int LIMIT = 30;

    private final AgentReportService service;

    public AgentReportController(AgentReportService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('IMPORTS:VIEW') and @storeAccess.canAccess(authentication, #storeId)")
    public List<AgentReportFileResponse> recent(@PathVariable UUID storeId) {
        return service.recent(storeId, LIMIT);
    }
}
