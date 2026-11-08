package com.intermanage.backend.staff.controller;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.staff.dto.ContractChangeRequest;
import com.intermanage.backend.staff.dto.ContractRequest;
import com.intermanage.backend.staff.dto.DisabilityRequest;
import com.intermanage.backend.staff.dto.LeaveOpeningRequest;
import com.intermanage.backend.staff.dto.LeaveRequest;
import com.intermanage.backend.staff.dto.StaffProfileRequest;
import com.intermanage.backend.staff.dto.StaffRecordResponse;
import com.intermanage.backend.staff.dto.StaffRecordRow;
import com.intermanage.backend.staff.dto.WorkPeriodRequest;
import com.intermanage.backend.staff.service.StaffRecordService;

import jakarta.validation.Valid;

/** Kadry. Osobne uprawnienie, bo profil zawiera dane szczególnej kategorii (art. 22(1b) par. 3 KP). */
@RestController
@RequestMapping("/api/stores/{storeId}/staff/records")
public class StaffRecordController {

    private static final String VIEW =
            "hasAuthority('STAFF_RECORDS:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT =
            "hasAuthority('STAFF_RECORDS:EDIT') and @storeAccess.canAccess(authentication, #storeId)";

    private final StaffRecordService records;

    public StaffRecordController(StaffRecordService records) {
        this.records = records;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public List<StaffRecordRow> list(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.list(storeId, principal.getId());
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public StaffRecordResponse get(@PathVariable UUID storeId, @PathVariable UUID id,
            @RequestParam(required = false) Integer year, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.get(storeId, id, year, principal.getId());
    }

    @PutMapping("/{id}/profile")
    @PreAuthorize(EDIT)
    public StaffRecordResponse saveProfile(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody StaffProfileRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveProfile(storeId, id, request, principal.getId());
    }

    @PostMapping("/{id}/contracts/{contractId}/changes")
    @PreAuthorize(EDIT)
    public StaffRecordResponse addContractChange(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID contractId, @Valid @RequestBody ContractChangeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.addContractChange(storeId, id, contractId, request, principal.getId());
    }

    @DeleteMapping("/{id}/contract-changes/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse removeContractChange(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.removeContractChange(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/contract-changes/{entryId}/restore")
    @PreAuthorize(EDIT)
    public StaffRecordResponse restoreContractChange(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.restoreContractChange(storeId, id, entryId, principal.getId());
    }

    @PutMapping("/{id}/leave-opening")
    @PreAuthorize(EDIT)
    public StaffRecordResponse saveOpening(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LeaveOpeningRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveOpening(storeId, id, request, principal.getId());
    }

    @PostMapping("/{id}/contracts")
    @PreAuthorize(EDIT)
    public StaffRecordResponse createContract(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody ContractRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveContract(storeId, id, null, request, principal.getId());
    }

    @PutMapping("/{id}/contracts/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse updateContract(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @Valid @RequestBody ContractRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveContract(storeId, id, entryId, request, principal.getId());
    }

    @DeleteMapping("/{id}/contracts/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse removeContract(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.removeContract(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/contracts/{entryId}/restore")
    @PreAuthorize(EDIT)
    public StaffRecordResponse restoreContract(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.restoreContract(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/work-periods")
    @PreAuthorize(EDIT)
    public StaffRecordResponse createWorkPeriod(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody WorkPeriodRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveWorkPeriod(storeId, id, null, request, principal.getId());
    }

    @PutMapping("/{id}/work-periods/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse updateWorkPeriod(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @Valid @RequestBody WorkPeriodRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveWorkPeriod(storeId, id, entryId, request, principal.getId());
    }

    @DeleteMapping("/{id}/work-periods/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse removeWorkPeriod(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.removeWorkPeriod(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/work-periods/{entryId}/restore")
    @PreAuthorize(EDIT)
    public StaffRecordResponse restoreWorkPeriod(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.restoreWorkPeriod(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/disabilities")
    @PreAuthorize(EDIT)
    public StaffRecordResponse createDisability(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody DisabilityRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveDisability(storeId, id, null, request, principal.getId());
    }

    @PutMapping("/{id}/disabilities/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse updateDisability(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @Valid @RequestBody DisabilityRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveDisability(storeId, id, entryId, request, principal.getId());
    }

    @DeleteMapping("/{id}/disabilities/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse removeDisability(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.removeDisability(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/disabilities/{entryId}/restore")
    @PreAuthorize(EDIT)
    public StaffRecordResponse restoreDisability(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.restoreDisability(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/leaves")
    @PreAuthorize(EDIT)
    public StaffRecordResponse createLeave(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LeaveRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveLeave(storeId, id, null, request, principal.getId());
    }

    @PutMapping("/{id}/leaves/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse updateLeave(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @Valid @RequestBody LeaveRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.saveLeave(storeId, id, entryId, request, principal.getId());
    }

    @DeleteMapping("/{id}/leaves/{entryId}")
    @PreAuthorize(EDIT)
    public StaffRecordResponse removeLeave(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.removeLeave(storeId, id, entryId, principal.getId());
    }

    @PostMapping("/{id}/leaves/{entryId}/restore")
    @PreAuthorize(EDIT)
    public StaffRecordResponse restoreLeave(@PathVariable UUID storeId, @PathVariable UUID id,
            @PathVariable UUID entryId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return records.restoreLeave(storeId, id, entryId, principal.getId());
    }
}
