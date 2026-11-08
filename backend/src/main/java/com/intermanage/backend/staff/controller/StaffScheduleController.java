package com.intermanage.backend.staff.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.staff.dto.ScheduleChangeRequest;
import com.intermanage.backend.staff.dto.ScheduleDepartment;
import com.intermanage.backend.staff.dto.ScheduleLayoutRequest;
import com.intermanage.backend.staff.dto.ScheduleOpenHolidayRequest;
import com.intermanage.backend.staff.dto.SchedulePublishRequest;
import com.intermanage.backend.staff.dto.ScheduleResponse;
import com.intermanage.backend.staff.dto.ScheduleSettings;
import com.intermanage.backend.staff.dto.ScheduleSettingsRequest;
import com.intermanage.backend.staff.dto.ScheduleRateRequest;
import com.intermanage.backend.staff.dto.ScheduleTimeOffRequest;
import com.intermanage.backend.staff.service.StaffScheduleService;

import jakarta.validation.Valid;

/** Grafik. Uprawnienie mówi, kto w ogóle układa; który dział, sprawdza serwis (kierownik tylko swój). */
@RestController
@RequestMapping("/api/stores/{storeId}/staff/schedule")
public class StaffScheduleController {

    private static final String VIEW =
            "hasAuthority('STAFF_SCHEDULE:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('STAFF_SCHEDULE:EDIT') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String CONFIGURE = "hasAuthority('STAFF_SCHEDULE:VIEW') and hasAuthority('STAFF:EDIT')"
            + " and @storeAccess.canAccess(authentication, #storeId)";

    private final StaffScheduleService schedules;

    public StaffScheduleController(StaffScheduleService schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/departments")
    @PreAuthorize(VIEW)
    public List<ScheduleDepartment> departments(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.departments(storeId, principal);
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public ScheduleResponse get(@PathVariable UUID storeId, @RequestParam UUID departmentId, @RequestParam String month,
            @RequestParam(required = false) Integer version, @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.get(storeId, departmentId, month, version, principal);
    }

    @PutMapping("/entries")
    @PreAuthorize(EDIT)
    public ScheduleResponse change(@PathVariable UUID storeId, @Valid @RequestBody ScheduleChangeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.change(storeId, request, principal);
    }

    @PostMapping("/publish")
    @PreAuthorize(EDIT)
    public ScheduleResponse publish(@PathVariable UUID storeId, @Valid @RequestBody SchedulePublishRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.publish(storeId, request, principal);
    }

    @PutMapping("/layout")
    @PreAuthorize(EDIT)
    public ScheduleResponse saveLayout(@PathVariable UUID storeId, @Valid @RequestBody ScheduleLayoutRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.saveLayout(storeId, request, principal);
    }

    @PutMapping("/rates")
    @PreAuthorize(EDIT)
    public ScheduleResponse rate(@PathVariable UUID storeId, @Valid @RequestBody ScheduleRateRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.rate(storeId, request, principal);
    }

    @PutMapping("/time-off")
    @PreAuthorize(EDIT)
    public ScheduleResponse timeOff(@PathVariable UUID storeId, @Valid @RequestBody ScheduleTimeOffRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.timeOff(storeId, request, principal);
    }

    @PutMapping("/open-holidays")
    @PreAuthorize(CONFIGURE)
    public ScheduleResponse openHoliday(@PathVariable UUID storeId,
            @Valid @RequestBody ScheduleOpenHolidayRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.openHoliday(storeId, request, principal);
    }

    @GetMapping("/settings/{departmentId}")
    @PreAuthorize(CONFIGURE)
    public ScheduleSettings settings(@PathVariable UUID storeId, @PathVariable UUID departmentId) {
        return schedules.settings(storeId, departmentId);
    }

    @PutMapping("/settings/{departmentId}")
    @PreAuthorize(CONFIGURE)
    public ScheduleSettings saveSettings(@PathVariable UUID storeId, @PathVariable UUID departmentId,
            @Valid @RequestBody ScheduleSettingsRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return schedules.saveSettings(storeId, departmentId, request, principal.getId());
    }
}
