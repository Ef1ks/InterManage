package com.intermanage.backend.staff.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.RoleRepository;
import com.intermanage.backend.auth.service.RoleGrantService;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.dto.DepartmentRequest;
import com.intermanage.backend.staff.dto.DepartmentResponse;
import com.intermanage.backend.staff.dto.PositionRequest;
import com.intermanage.backend.staff.dto.PositionResponse;
import com.intermanage.backend.staff.dto.RoleOption;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffPosition;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.mapper.StaffMapper;
import com.intermanage.backend.staff.repository.StaffDepartmentRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffPositionRepository;
import com.intermanage.backend.staff.repository.StaffShiftRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Działy firmy i ich stanowiska, wspólne dla jej sklepów. Stanowisko jest ściśle związane z rolą: zmiana roli stanowiska od razu zmienia
 * uprawnienia jego pracowników. Godziny stanowiska są osobne w każdym sklepie, więc odczyt i zapis dotyczą sklepu z
 * adresu. Każda zmiana zwraca całą strukturę.
 */
@Service
public class StaffStructureService {

    private final StaffDepartmentRepository departments;
    private final StaffPositionRepository positions;
    private final StaffEmployeeRepository employees;
    private final StaffShiftRepository shifts;
    private final StaffShiftService shiftService;
    private final StoreRepository stores;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final RoleGrantService grants;
    private final StaffMapper mapper;

    public StaffStructureService(StaffDepartmentRepository departments, StaffPositionRepository positions,
            StaffEmployeeRepository employees, StaffShiftRepository shifts, StaffShiftService shiftService,
            StoreRepository stores, RoleRepository roles, AppUserRepository users, RoleGrantService grants,
            StaffMapper mapper) {
        this.departments = departments;
        this.positions = positions;
        this.employees = employees;
        this.shifts = shifts;
        this.shiftService = shiftService;
        this.stores = stores;
        this.roles = roles;
        this.users = users;
        this.grants = grants;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> list(UUID storeId) {
        String company = company(storeId);
        List<StaffPosition> all = positions.findActive(company);
        Map<UUID, List<StaffShift>> shiftsByPosition = all.isEmpty() ? Map.of()
                : shifts.findActiveOfPositions(all.stream().map(StaffPosition::getId).toList(), storeId).stream()
                        .collect(Collectors.groupingBy(shift -> shift.getPosition().getId()));
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : employees.countActivePerPosition(company)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        Map<UUID, List<PositionResponse>> byDepartment = all.stream().collect(Collectors.groupingBy(
                position -> position.getDepartment().getId(),
                Collectors.mapping(position -> mapper.toResponse(position,
                        shiftsByPosition.getOrDefault(position.getId(), List.of()),
                        counts.getOrDefault(position.getId(), 0L)), Collectors.toList())));
        return departments.findActive(company).stream()
                .map(department -> mapper.toResponse(department,
                        byDepartment.getOrDefault(department.getId(), List.of())))
                .toList();
    }

    /** Role do wyboru przy stanowisku: bez właściciela, bo to nie jest stanowisko w sklepie. */
    @Transactional(readOnly = true)
    public List<RoleOption> roles(UUID actorId) {
        return roles.findAllWithPermissions().stream()
                .filter(role -> !role.isOwner())
                .sorted(Comparator.comparing(role -> role.getName().toLowerCase()))
                .map(role -> new RoleOption(role.getId(), role.getName(), grants.canGrant(actorId, role)))
                .toList();
    }

    @Transactional
    public List<DepartmentResponse> createDepartment(UUID storeId, DepartmentRequest request, UUID actorId) {
        String name = request.name().strip();
        requireFreeDepartmentName(storeId, name, null);
        departments.saveAndFlush(new StaffDepartment(company(storeId), name, user(actorId)));
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> updateDepartment(UUID storeId, UUID id, DepartmentRequest request) {
        StaffDepartment department = activeDepartment(storeId, id);
        String name = request.name().strip();
        requireFreeDepartmentName(storeId, name, id);
        department.setName(name);
        departments.flush();
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> deleteDepartment(UUID storeId, UUID id, UUID actorId) {
        StaffDepartment department = activeDepartment(storeId, id);
        if (positions.countActiveInDepartment(id) > 0) {
            throw ApiException.conflict("Dział ma stanowiska. Najpierw je usuń albo przenieś do innego działu.");
        }
        department.markDeleted(user(actorId));
        departments.flush();
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> restoreDepartment(UUID storeId, UUID id) {
        StaffDepartment department = departments.findInCompany(company(storeId), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
        if (department.getDeletedAt() != null) {
            requireFreeDepartmentName(storeId, department.getName(), id);
            department.restore();
            departments.flush();
        }
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> createPosition(UUID storeId, PositionRequest request, UUID actorId) {
        StaffDepartment department = activeDepartment(storeId, request.departmentId());
        String name = request.name().strip();
        requireFreePositionName(department.getId(), name, null);
        Role role = positionRole(request.roleId());
        grants.requireGrantable(actorId, role);
        AppUser actor = user(actorId);
        StaffPosition position = positions.saveAndFlush(new StaffPosition(department, name, role, actor));
        Store store = stores.getReferenceById(storeId);
        shiftService.replace(List.of(), request.shifts(), hours -> StaffShift.ofPosition(position, store, hours, actor),
                actor);
        shifts.flush();
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> updatePosition(UUID storeId, UUID id, PositionRequest request, UUID actorId) {
        StaffPosition position = positions.findInCompany(company(storeId), id).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono stanowiska"));
        StaffDepartment department = activeDepartment(storeId, request.departmentId());
        String name = request.name().strip();
        requireFreePositionName(department.getId(), name, id);

        Role role = positionRole(request.roleId());
        boolean roleChanged = !role.getId().equals(position.getRole().getId());
        if (roleChanged) {
            grants.requireGrantable(actorId, position.getRole());
            grants.requireGrantable(actorId, role);
        }
        position.update(department, name, role);
        if (roleChanged) {
            employees.findActiveOnPosition(id).forEach(employee -> applyRole(employee, role));
        }

        AppUser actor = user(actorId);
        Store store = stores.getReferenceById(storeId);
        shiftService.replace(shifts.findActiveOfPositions(List.of(id), storeId), request.shifts(),
                hours -> StaffShift.ofPosition(position, store, hours, actor), actor);
        shifts.flush();
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> deletePosition(UUID storeId, UUID id, UUID actorId) {
        StaffPosition position = positions.findInCompany(company(storeId), id).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono stanowiska"));
        long assigned = employees.countActiveOnPosition(id);
        if (assigned > 0) {
            throw ApiException.conflict("Na stanowisku są pracownicy (" + assigned + "). Najpierw zmień im stanowisko.");
        }
        position.markDeleted(user(actorId));
        positions.flush();
        return list(storeId);
    }

    @Transactional
    public List<DepartmentResponse> restorePosition(UUID storeId, UUID id) {
        StaffPosition position = positions.findInCompany(company(storeId), id)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono stanowiska"));
        if (position.getDeletedAt() != null) {
            if (position.getRole() == null) {
                throw ApiException.conflict("Rola tego stanowiska została usunięta. Załóż stanowisko od nowa.");
            }
            if (position.getDepartment().getDeletedAt() != null) {
                throw ApiException.conflict("Dział tego stanowiska jest usunięty. Najpierw go przywróć.");
            }
            requireFreePositionName(position.getDepartment().getId(), position.getName(), id);
            position.restore();
            positions.flush();
        }
        return list(storeId);
    }

    /** Właściciel zostaje właścicielem także na stanowisku z inną rolą. */
    static void applyRole(StaffEmployee employee, Role role) {
        AppUser user = employee.getUser();
        if (!user.getRole().isOwner()) {
            user.setRole(role);
        }
    }

    private StaffDepartment activeDepartment(UUID storeId, UUID id) {
        return departments.findInCompany(company(storeId), id).filter(department -> department.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
    }

    private Role positionRole(UUID roleId) {
        Role role = roles.findWithPermissionsById(roleId)
                .orElseThrow(() -> ApiException.badRequest("Wybrana rola nie istnieje"));
        if (role.isOwner()) {
            throw ApiException.badRequest("Stanowisko nie może mieć roli Właściciel");
        }
        return role;
    }

    private void requireFreeDepartmentName(UUID storeId, String name, UUID exceptId) {
        if (departments.existsActiveName(company(storeId), name, exceptId)) {
            throw ApiException.conflict("Dział „" + name + "” już istnieje");
        }
    }

    private void requireFreePositionName(UUID departmentId, String name, UUID exceptId) {
        if (positions.existsActiveName(departmentId, name, exceptId)) {
            throw ApiException.conflict("Stanowisko „" + name + "” już jest w tym dziale");
        }
    }

    private String company(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu")).companyKey();
    }

    private AppUser user(UUID userId) {
        return users.getReferenceById(userId);
    }
}
