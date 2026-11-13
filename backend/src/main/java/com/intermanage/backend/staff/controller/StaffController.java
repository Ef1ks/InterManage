package com.intermanage.backend.staff.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.service.UserService;
import com.intermanage.backend.staff.dto.AccountOption;
import com.intermanage.backend.staff.dto.AssignEmployeeRequest;
import com.intermanage.backend.staff.dto.CreateEmployeeRequest;
import com.intermanage.backend.staff.dto.CreatePlainEmployeeRequest;
import com.intermanage.backend.staff.dto.DepartmentRequest;
import com.intermanage.backend.staff.dto.DepartmentResponse;
import com.intermanage.backend.staff.dto.EmployeeResponse;
import com.intermanage.backend.staff.dto.InviteAccountRequest;
import com.intermanage.backend.staff.dto.LinkAccountRequest;
import com.intermanage.backend.staff.dto.LoginSuggestion;
import com.intermanage.backend.staff.dto.PositionRequest;
import com.intermanage.backend.staff.dto.RoleOption;
import com.intermanage.backend.staff.dto.TransferEmployeeRequest;
import com.intermanage.backend.staff.dto.UpdateEmployeeRequest;
import com.intermanage.backend.staff.service.StaffEmployeeService;
import com.intermanage.backend.staff.service.StaffStructureService;
import com.intermanage.backend.store.dto.StoreSummary;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/stores/{storeId}/staff")
public class StaffController {

    private static final String VIEW = "hasAuthority('STAFF:VIEW') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String EDIT = "hasAuthority('STAFF:EDIT') and @storeAccess.canAccess(authentication, #storeId)";
    private static final String OWNER = "hasAuthority('ROLE_OWNER') and @storeAccess.canAccess(authentication, #storeId)";

    private final StaffStructureService structure;
    private final StaffEmployeeService employees;
    private final UserService users;

    public StaffController(StaffStructureService structure, StaffEmployeeService employees, UserService users) {
        this.structure = structure;
        this.employees = employees;
        this.users = users;
    }

    @GetMapping("/structure")
    @PreAuthorize(VIEW)
    public List<DepartmentResponse> structure(@PathVariable UUID storeId) {
        return structure.list(storeId);
    }

    @GetMapping("/roles")
    @PreAuthorize(EDIT)
    public List<RoleOption> roles(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.roles(principal.getId());
    }

    @PostMapping("/departments")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> createDepartment(@PathVariable UUID storeId,
            @Valid @RequestBody DepartmentRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.createDepartment(storeId, request, principal.getId());
    }

    @PutMapping("/departments/{id}")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> updateDepartment(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody DepartmentRequest request) {
        return structure.updateDepartment(storeId, id, request);
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> deleteDepartment(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.deleteDepartment(storeId, id, principal.getId());
    }

    @PostMapping("/departments/{id}/restore")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> restoreDepartment(@PathVariable UUID storeId, @PathVariable UUID id) {
        return structure.restoreDepartment(storeId, id);
    }

    @PostMapping("/positions")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> createPosition(@PathVariable UUID storeId,
            @Valid @RequestBody PositionRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.createPosition(storeId, request, principal.getId());
    }

    @PutMapping("/positions/{id}")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> updatePosition(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody PositionRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.updatePosition(storeId, id, request, principal.getId());
    }

    @DeleteMapping("/positions/{id}")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> deletePosition(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return structure.deletePosition(storeId, id, principal.getId());
    }

    @PostMapping("/positions/{id}/restore")
    @PreAuthorize(EDIT)
    public List<DepartmentResponse> restorePosition(@PathVariable UUID storeId, @PathVariable UUID id) {
        return structure.restorePosition(storeId, id);
    }

    @GetMapping("/employees")
    @PreAuthorize(VIEW)
    public List<EmployeeResponse> employees(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.list(storeId, principal.getId());
    }

    @GetMapping("/accounts")
    @PreAuthorize(EDIT)
    public List<AccountOption> accounts(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.accounts(storeId, principal.getId());
    }

    @GetMapping("/login-suggestion")
    @PreAuthorize(EDIT)
    public LoginSuggestion loginSuggestion(@PathVariable UUID storeId, @RequestParam String name) {
        return new LoginSuggestion(users.suggestUsername(name));
    }

    @PostMapping("/employees")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse invite(@PathVariable UUID storeId, @Valid @RequestBody CreateEmployeeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.invite(storeId, request, principal.getId());
    }

    @PostMapping("/employees/without-account")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse createWithoutAccount(@PathVariable UUID storeId,
            @Valid @RequestBody CreatePlainEmployeeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.createWithoutAccount(storeId, request, principal.getId());
    }

    @PostMapping("/employees/{id}/account")
    @PreAuthorize(EDIT)
    public EmployeeResponse linkAccount(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody LinkAccountRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.linkAccount(storeId, id, request, principal.getId());
    }

    @PostMapping("/employees/{id}/account/invite")
    @PreAuthorize(EDIT)
    public EmployeeResponse inviteAccount(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody InviteAccountRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.inviteAccount(storeId, id, request, principal.getId());
    }

    @PostMapping("/employees/existing")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse assign(@PathVariable UUID storeId, @Valid @RequestBody AssignEmployeeRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.assign(storeId, request, principal.getId());
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize(EDIT)
    public EmployeeResponse update(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody UpdateEmployeeRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.update(storeId, id, request, principal.getId());
    }

    @GetMapping("/company-stores")
    @PreAuthorize(VIEW)
    public List<StoreSummary> companyStores(@PathVariable UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.companyStores(storeId, principal.getId());
    }

    @PostMapping("/employees/{id}/transfer")
    @PreAuthorize(EDIT)
    public EmployeeResponse transfer(@PathVariable UUID storeId, @PathVariable UUID id,
            @Valid @RequestBody TransferEmployeeRequest request, @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.transfer(storeId, id, request, principal.getId());
    }

    @DeleteMapping("/employees/{id}")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        employees.end(storeId, id, principal.getId());
    }

    @PostMapping("/employees/{id}/restore")
    @PreAuthorize(EDIT)
    public EmployeeResponse restore(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.restore(storeId, id, principal.getId());
    }

    /** Usunięcie pracownika po zakończeniu pracy: tylko właściciel, znacznik do cofnięcia. */
    @PostMapping("/employees/{id}/delete")
    @PreAuthorize(OWNER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        employees.delete(storeId, id, principal.getId());
    }

    @PostMapping("/employees/{id}/undelete")
    @PreAuthorize(OWNER)
    public EmployeeResponse undelete(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return employees.undelete(storeId, id, principal.getId());
    }

    @PostMapping("/employees/{id}/invitation")
    @PreAuthorize(EDIT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendInvitation(@PathVariable UUID storeId, @PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        employees.resendInvitation(storeId, id, principal.getId());
    }
}
