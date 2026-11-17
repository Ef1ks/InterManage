package com.intermanage.backend.staff.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.staff.dto.MyTimeAnomaliesResponse;
import com.intermanage.backend.staff.dto.TimeRequestInput;
import com.intermanage.backend.staff.dto.TimeRequestResponse;
import com.intermanage.backend.staff.service.StaffTimeAnomalyService;
import com.intermanage.backend.staff.service.StaffTimeRequestService;

import jakarta.validation.Valid;

/** Własne wnioski zalogowanego pracownika (aplikacja mobilna). Bez uprawnień: każdy widzi i składa tylko swoje. */
@RestController
@RequestMapping("/api/me")
public class MyTimeRequestController {

    private final StaffTimeRequestService requests;
    private final StaffTimeAnomalyService anomalies;

    public MyTimeRequestController(StaffTimeRequestService requests, StaffTimeAnomalyService anomalies) {
        this.requests = requests;
        this.anomalies = anomalies;
    }

    /** Różnice w karcie z ostatnich 14 dni, na które można złożyć wniosek. */
    @GetMapping("/time-anomalies")
    public MyTimeAnomaliesResponse anomalies(@AuthenticationPrincipal AppUserPrincipal principal) {
        return anomalies.own(principal.getId());
    }

    @GetMapping("/time-requests")
    public List<TimeRequestResponse> own(@AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.own(principal.getId());
    }

    @PostMapping("/time-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeRequestResponse submit(@Valid @RequestBody TimeRequestInput.Create input,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.submitOwn(principal.getId(), input);
    }

    @PostMapping("/time-requests/{requestId}/cancel")
    public TimeRequestResponse cancel(@PathVariable UUID requestId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return requests.cancelOwn(principal.getId(), requestId);
    }
}
