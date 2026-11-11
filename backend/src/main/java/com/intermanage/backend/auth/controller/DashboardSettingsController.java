package com.intermanage.backend.auth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.dto.DashboardSettingsResponse;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.service.DashboardSettingsService;

import jakarta.validation.Valid;

/** Ustawienia pulpitu zalogowanego konta: co pokazuje wykres wyników sklepu. */
@RestController
@RequestMapping("/api/auth/dashboard-settings")
public class DashboardSettingsController {

    private final DashboardSettingsService service;

    public DashboardSettingsController(DashboardSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardSettingsResponse get(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.get(principal.getId());
    }

    @PutMapping
    public DashboardSettingsResponse save(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody DashboardSettingsResponse request) {
        return service.save(principal.getId(), request);
    }
}
