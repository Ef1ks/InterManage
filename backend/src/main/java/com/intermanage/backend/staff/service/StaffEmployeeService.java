package com.intermanage.backend.staff.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.service.RoleGrantService;
import com.intermanage.backend.auth.service.UserService;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.dto.AccountOption;
import com.intermanage.backend.staff.dto.AssignEmployeeRequest;
import com.intermanage.backend.staff.dto.CreateEmployeeRequest;
import com.intermanage.backend.staff.dto.CreatePlainEmployeeRequest;
import com.intermanage.backend.staff.dto.EmployeeResponse;
import com.intermanage.backend.staff.dto.InviteAccountRequest;
import com.intermanage.backend.staff.dto.LinkAccountRequest;
import com.intermanage.backend.staff.dto.ShiftRequest;
import com.intermanage.backend.staff.dto.TransferEmployeeRequest;
import com.intermanage.backend.staff.dto.UpdateEmployeeRequest;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffEmployeeMove;
import com.intermanage.backend.staff.entity.StaffPosition;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.mapper.StaffMapper;
import com.intermanage.backend.staff.repository.StaffEmployeeMoveRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffPositionRepository;
import com.intermanage.backend.staff.repository.StaffShiftRepository;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.mapper.StoreMapper;
import com.intermanage.backend.store.repository.StoreRepository;
import com.intermanage.backend.store.service.StoreService;

/**
 * Pracownicy firmy. Pracownik to konto w aplikacji (albo samo imię i nazwisko, gdy nie korzysta z aplikacji) i
 * stanowisko, a rola konta wynika ze stanowiska. Należy do firmy,
 * więc może przejść do innego sklepu z tym samym NIP-em, a kadry widzą naraz wszystkie swoje sklepy firmy. Stałe
 * godziny mogą być spoza godzin stanowiska, bo zdarzają się wyjątki. Osoba zarządzająca nie może zmieniać kont z
 * większymi uprawnieniami niż jej własne ({@link RoleGrantService}).
 */
@Service
public class StaffEmployeeService {

    private final StaffEmployeeRepository employees;
    private final StaffEmployeeMoveRepository moves;
    private final StaffPositionRepository positions;
    private final StaffShiftRepository shifts;
    private final StaffShiftService shiftService;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final UserService userService;
    private final RoleGrantService grants;
    private final StaffMapper mapper;
    private final StoreService storeService;
    private final StoreMapper storeMapper;

    public StaffEmployeeService(StaffEmployeeRepository employees, StaffEmployeeMoveRepository moves,
            StaffPositionRepository positions, StaffShiftRepository shifts, StaffShiftService shiftService,
            StoreRepository stores, AppUserRepository users, UserService userService, RoleGrantService grants,
            StaffMapper mapper, StoreService storeService, StoreMapper storeMapper) {
        this.employees = employees;
        this.moves = moves;
        this.positions = positions;
        this.shifts = shifts;
        this.shiftService = shiftService;
        this.stores = stores;
        this.users = users;
        this.userService = userService;
        this.grants = grants;
        this.mapper = mapper;
        this.storeService = storeService;
        this.storeMapper = storeMapper;
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> list(UUID storeId, UUID actorId) {
        List<StaffEmployee> all = employees.findInStores(companyStoreIds(storeId, actorId));
        Map<UUID, List<StaffShift>> byEmployee = all.isEmpty() ? Map.of()
                : shifts.findActiveOfEmployees(all.stream().map(StaffEmployee::getId).toList()).stream()
                        .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));
        return all.stream()
                .map(employee -> mapper.toResponse(employee, byEmployee.getOrDefault(employee.getId(), List.of())))
                .toList();
    }

    /** Konta z dostępem do sklepu, które nie mają jeszcze stanowiska. */
    @Transactional(readOnly = true)
    public List<AccountOption> accounts(UUID storeId, UUID actorId) {
        return users.findAllWithRole().stream()
                .filter(AppUser::isEnabled)
                .filter(user -> user.getRole().isOwner()
                        || user.getStores().stream().anyMatch(store -> store.getId().equals(storeId)))
                .filter(user -> !employees.existsActiveForUser(user.getId()))
                .filter(user -> grants.canGrant(actorId, user.getRole()))
                .map(mapper::toAccountOption)
                .toList();
    }

    @Transactional
    public EmployeeResponse invite(UUID storeId, CreateEmployeeRequest request, UUID actorId) {
        Store store = store(storeId);
        StaffPosition position = activePosition(store, request.positionId());
        grants.requireGrantable(actorId, position.getRole());
        String username = request.username() == null || request.username().isBlank()
                ? userService.suggestUsername(request.displayName()) : request.username();
        if (username.isEmpty()) {
            throw ApiException.badRequest("Nie da się złożyć loginu z imienia i nazwiska. Wpisz go ręcznie.");
        }
        AppUser user = userService.invite(username, request.displayName(), request.email(), position.getRole(),
                Set.of(store));
        return save(store, user, position, request.shifts(), actorId);
    }

    /** Bez konta: tylko imię i nazwisko, więc bez loginu, e-maila i roli. */
    @Transactional
    public EmployeeResponse createWithoutAccount(UUID storeId, CreatePlainEmployeeRequest request, UUID actorId) {
        Store store = store(storeId);
        StaffPosition position = activePosition(store, request.positionId());
        AppUser actor = users.getReferenceById(actorId);
        StaffEmployee employee = employees.saveAndFlush(
                StaffEmployee.withoutAccount(store, request.displayName().strip(), position, actor));
        shiftService.replace(List.of(), request.shifts(), hours -> StaffShift.ofEmployee(employee, hours, actor), actor);
        shifts.flush();
        return response(employee);
    }

    /** Pracownik bez konta dostaje istniejące konto (bez stanowiska). Rola konta idzie za stanowiskiem, jak przy dodaniu. */
    @Transactional
    public EmployeeResponse linkAccount(UUID storeId, UUID id, LinkAccountRequest request, UUID actorId) {
        StaffEmployee employee = withoutAccount(storeId, id, actorId);
        AppUser user = users.findWithRoleById(request.userId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono konta"));
        if (!user.isEnabled()) {
            throw ApiException.conflict("To konto jest zablokowane");
        }
        grants.requireGrantable(actorId, user.getRole());
        grants.requireGrantable(actorId, employee.getPosition().getRole());
        requireNotEmployed(user);
        attach(employee, user);
        return response(employee);
    }

    /** Pracownik bez konta dostaje nowe konto z jego imieniem i nazwiskiem; link aktywacyjny idzie na e-mail. */
    @Transactional
    public EmployeeResponse inviteAccount(UUID storeId, UUID id, InviteAccountRequest request, UUID actorId) {
        StaffEmployee employee = withoutAccount(storeId, id, actorId);
        StaffPosition position = employee.getPosition();
        grants.requireGrantable(actorId, position.getRole());
        String name = employee.getDisplayName();
        String username = request.username() == null || request.username().isBlank()
                ? userService.suggestUsername(name) : request.username();
        if (username.isEmpty()) {
            throw ApiException.badRequest("Nie da się złożyć loginu z imienia i nazwiska. Wpisz go ręcznie.");
        }
        AppUser user = userService.invite(username, name, request.email(), position.getRole(),
                Set.of(employee.getStore()));
        attach(employee, user);
        return response(employee);
    }

    private void attach(StaffEmployee employee, AppUser user) {
        employee.linkAccount(user);
        if (!user.getRole().isOwner()) {
            user.setRole(employee.getPosition().getRole());
            user.getStores().add(employee.getStore());
        }
        employees.flush();
    }

    private StaffEmployee withoutAccount(UUID storeId, UUID id, UUID actorId) {
        StaffEmployee employee = activeEmployee(storeId, id, actorId);
        if (employee.hasAccount()) {
            throw ApiException.conflict(employee.getDisplayName() + " ma już konto w aplikacji");
        }
        return employee;
    }

    @Transactional
    public EmployeeResponse assign(UUID storeId, AssignEmployeeRequest request, UUID actorId) {
        Store store = store(storeId);
        StaffPosition position = activePosition(store, request.positionId());
        AppUser user = users.findWithRoleById(request.userId())
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono konta"));
        grants.requireGrantable(actorId, user.getRole());
        grants.requireGrantable(actorId, position.getRole());
        requireNotEmployed(user);
        if (!user.getRole().isOwner()) {
            user.setRole(position.getRole());
            user.getStores().add(store);
        }
        return save(store, user, position, request.shifts(), actorId);
    }

    @Transactional
    public EmployeeResponse update(UUID storeId, UUID id, UpdateEmployeeRequest request, UUID actorId) {
        StaffEmployee employee = activeEmployee(storeId, id, actorId);
        AppUser user = employee.getUser();
        requireGrantable(actorId, user);
        if (!employee.getPosition().getId().equals(request.positionId())) {
            StaffPosition position = activePosition(employee.getStore(), request.positionId());
            if (user != null) {
                grants.requireGrantable(actorId, position.getRole());
            }
            employee.setPosition(position);
            StaffStructureService.applyRole(employee, position.getRole());
        }
        if (user != null) {
            userService.updateProfile(user, request.displayName(), request.email());
        } else {
            employee.setFullName(request.displayName().strip());
        }
        AppUser actor = users.getReferenceById(actorId);
        shiftService.replace(shifts.findActiveOfEmployees(List.of(id)), request.shifts(),
                hours -> StaffShift.ofEmployee(employee, hours, actor), actor);
        shifts.flush();
        return response(employee);
    }

    /** Aktywne sklepy firmy, do których użytkownik ma dostęp, i zawsze sklep z adresu (właściciel otwiera też wyłączony). */
    @Transactional(readOnly = true)
    public List<StoreSummary> companyStores(UUID storeId, UUID actorId) {
        Store current = store(storeId);
        if (current.getNip() == null) {
            return List.of(storeMapper.toSummary(current));
        }
        Set<UUID> accessible = accessibleStoreIds(actorId);
        return Stream.concat(Stream.of(current), stores.findActiveByNip(current.getNip()).stream()
                        .filter(store -> !store.getId().equals(storeId) && accessible.contains(store.getId())))
                .sorted(Comparator.comparing(Store::getNumber))
                .map(storeMapper::toSummary)
                .toList();
    }

    /** Konto i login zostają. Rola idzie za stanowiskiem, a dostęp do sklepu za pracownikiem. */
    @Transactional
    public EmployeeResponse transfer(UUID storeId, UUID id, TransferEmployeeRequest request, UUID actorId) {
        StaffEmployee employee = activeEmployee(storeId, id, actorId);
        AppUser user = employee.getUser();
        requireGrantable(actorId, user);

        Store source = employee.getStore();
        Store target = store(request.storeId());
        if (target.getId().equals(source.getId())) {
            throw ApiException.badRequest("Pracownik już pracuje w tym sklepie");
        }
        if (source.getNip() == null || !Objects.equals(source.getNip(), target.getNip())) {
            throw ApiException.badRequest("Pracownika można przenieść tylko do sklepu tej samej firmy (ten sam NIP)");
        }
        if (!target.isActive()) {
            throw ApiException.badRequest("Ten sklep jest wyłączony");
        }
        if (!accessibleStoreIds(actorId).contains(target.getId())) {
            throw ApiException.forbidden("Nie masz dostępu do sklepu " + target.getNumber());
        }
        StaffPosition position = activePosition(target, request.positionId());
        if (user != null) {
            grants.requireGrantable(actorId, position.getRole());
        }

        AppUser actor = users.getReferenceById(actorId);
        moves.save(new StaffEmployeeMove(employee, source, target, employee.getPosition(), position, actor));
        employee.moveTo(target, position);
        StaffStructureService.applyRole(employee, position.getRole());
        if (user != null && !user.getRole().isOwner()) {
            user.getStores().removeIf(store -> store.getId().equals(source.getId()));
            user.getStores().add(target);
        }
        shiftService.replace(shifts.findActiveOfEmployees(List.of(id)), request.shifts(),
                hours -> StaffShift.ofEmployee(employee, hours, actor), actor);
        shifts.flush();
        return response(employee);
    }

    /** Koniec pracy blokuje konto (właściciela nie); przywrócenie je odblokowuje. */
    @Transactional
    public void end(UUID storeId, UUID id, UUID actorId) {
        StaffEmployee employee = activeEmployee(storeId, id, actorId);
        AppUser user = employee.getUser();
        requireGrantable(actorId, user);
        if (user != null && user.getId().equals(actorId)) {
            throw ApiException.conflict("Nie możesz zakończyć pracy na własnym koncie");
        }
        employee.markEnded(users.getReferenceById(actorId));
        if (user != null && !user.getRole().isOwner()) {
            user.setEnabled(false);
        }
    }

    @Transactional
    public EmployeeResponse restore(UUID storeId, UUID id, UUID actorId) {
        StaffEmployee employee = employeeInCompany(storeId, id, actorId);
        if (employee.getEndedAt() == null) {
            return response(employee);
        }
        AppUser user = employee.getUser();
        StaffPosition position = employee.getPosition();
        if (position.getDeletedAt() != null) {
            throw ApiException.conflict("Stanowisko tej osoby jest usunięte. Najpierw je przywróć.");
        }
        if (user != null) {
            grants.requireGrantable(actorId, user.getRole());
            grants.requireGrantable(actorId, position.getRole());
            requireNotEmployed(user);
        }
        employee.restore();
        StaffStructureService.applyRole(employee, position.getRole());
        if (user != null) {
            user.setEnabled(true);
        }
        employees.flush();
        return response(employee);
    }

    @Transactional
    public void resendInvitation(UUID storeId, UUID id, UUID actorId) {
        StaffEmployee employee = activeEmployee(storeId, id, actorId);
        if (!employee.hasAccount()) {
            throw ApiException.conflict("Ten pracownik nie ma konta w aplikacji");
        }
        grants.requireGrantable(actorId, employee.getUser().getRole());
        userService.resendActivationLink(employee.getUser().getId());
    }

    private EmployeeResponse save(Store store, AppUser user, StaffPosition position, List<ShiftRequest> requested,
            UUID actorId) {
        AppUser actor = users.getReferenceById(actorId);
        StaffEmployee employee = employees.saveAndFlush(new StaffEmployee(store, user, position, actor));
        shiftService.replace(List.of(), requested, hours -> StaffShift.ofEmployee(employee, hours, actor), actor);
        shifts.flush();
        return response(employee);
    }

    /** Pracownik bez konta nie ma uprawnień, więc zmienić go może każdy z {@code STAFF:EDIT}. */
    private void requireGrantable(UUID actorId, AppUser user) {
        if (user != null) {
            grants.requireGrantable(actorId, user.getRole());
        }
    }

    private Set<UUID> accessibleStoreIds(UUID actorId) {
        AppUser actor = users.findWithRoleById(actorId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        return storeService.accessibleStores(actor).stream().map(StoreSummary::id).collect(Collectors.toSet());
    }

    private EmployeeResponse response(StaffEmployee employee) {
        return mapper.toResponse(employee, shifts.findActiveOfEmployees(List.of(employee.getId())));
    }

    private void requireNotEmployed(AppUser user) {
        if (employees.existsActiveForUser(user.getId())) {
            throw ApiException.conflict(user.getDisplayName() + " ma już stanowisko w tym albo innym sklepie");
        }
    }

    List<UUID> companyStoreIds(UUID storeId, UUID actorId) {
        return companyStores(storeId, actorId).stream().map(StoreSummary::id).toList();
    }

    /** Pracownik z dowolnego sklepu firmy, ale tylko ze sklepu, do którego użytkownik ma dostęp. */
    StaffEmployee employeeInCompany(UUID storeId, UUID id, UUID actorId) {
        return employees.findWithDetails(id)
                .filter(employee -> companyStoreIds(storeId, actorId).contains(employee.getStore().getId()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
    }

    private StaffEmployee activeEmployee(UUID storeId, UUID id, UUID actorId) {
        StaffEmployee employee = employeeInCompany(storeId, id, actorId);
        if (employee.getEndedAt() != null) {
            throw ApiException.notFound("Nie znaleziono pracownika");
        }
        return employee;
    }

    private StaffPosition activePosition(Store store, UUID id) {
        return positions.findInCompany(store.companyKey(), id).filter(position -> position.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.badRequest("Wybrane stanowisko nie istnieje"));
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }
}
