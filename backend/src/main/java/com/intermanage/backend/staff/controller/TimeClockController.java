package com.intermanage.backend.staff.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.staff.dto.TimeRequests;
import com.intermanage.backend.staff.service.StaffTimeService;

import jakarta.validation.Valid;

/**
 * API dla czytników kart NFC: bez logowania i bez CSRF (wyjątek w {@code SecurityConfig}), czytnik przedstawia się
 * kluczem z Ustawień kart czasu pracy w nagłówku {@code Authorization: Bearer <klucz>}.
 *
 * <pre>
 * POST /api/time-clock/punches
 * {"cardUid": "04A2B3C4D5E6F7", "punchedAt": "2026-11-08T05:55:00Z"}   // punchedAt opcjonalny
 * → {"employeeName": "Jan Kowalski", "direction": "IN", "punchedAt": "..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/time-clock")
public class TimeClockController {

    private static final String BEARER = "Bearer ";

    private final StaffTimeService time;

    public TimeClockController(StaffTimeService time) {
        this.time = time;
    }

    @PostMapping("/punches")
    public StaffTimeService.ReaderResult punch(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
    String authorization, @Valid @RequestBody TimeRequests.ReaderPunch request) {
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip() : null;
        return time.readerPunch(token, request);
    }
}
