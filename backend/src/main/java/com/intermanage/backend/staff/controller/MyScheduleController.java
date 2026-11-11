package com.intermanage.backend.staff.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.staff.dto.MyScheduleResponse;
import com.intermanage.backend.staff.service.MyScheduleService;

/** Własny grafik zalogowanego pracownika. Bez uprawnień: każdy widzi tylko swoje zmiany. */
@RestController
@RequestMapping("/api/me/schedule")
public class MyScheduleController {

    private final MyScheduleService service;

    public MyScheduleController(MyScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public MyScheduleResponse schedule(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.of(principal.getId(), from, to);
    }
}
