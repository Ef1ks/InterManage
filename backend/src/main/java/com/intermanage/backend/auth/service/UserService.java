package com.intermanage.backend.auth.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.component.LoginNames;
import com.intermanage.backend.auth.dto.CreateUserRequest;
import com.intermanage.backend.auth.dto.UpdateUserRequest;
import com.intermanage.backend.auth.dto.UserResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.Role;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.mapper.UserMapper;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.RoleRepository;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.service.StoreService;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final ActivationService activationService;
    private final UserMapper userMapper;
    private final StoreService storeService;
    private final StaffEmployeeRepository employees;
    private final StoreRoleService ranks;

    public UserService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            ActivationService activationService, UserMapper userMapper, StoreService storeService,
            StaffEmployeeRepository employees, StoreRoleService ranks) {
        this.ranks = ranks;
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.activationService = activationService;
        this.userMapper = userMapper;
        this.storeService = storeService;
        this.employees = employees;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        Map<UUID, String> positions = new HashMap<>();
        for (Object[] row : employees.findActivePositionNames()) {
            positions.put((UUID) row[0], (String) row[1]);
        }
        return users.findAllWithRole().stream()
                .filter(user -> user.getDeletedAt() == null)
                .map(user -> userMapper.toResponse(user, positions.get(user.getId())))
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, UUID actorId) {
        Set<Store> stores = Set.copyOf(storeService.loadAll(request.storeIds()));
        AppUser user = invite(request.username(), request.displayName(), request.email(),
                Boolean.TRUE.equals(request.owner()) ? ownerRole() : accountRole(), stores);
        applyRanks(user, stores, request.storeRoles(), null, actorId);
        return userMapper.toResponse(user, null);
    }

    /** Konto pracownika (bez rangi: nadaje ją stanowisko w sklepie). */
    @Transactional
    public AppUser invite(String login, String displayName, String email, Set<Store> stores) {
        return invite(login, displayName, email, accountRole(), stores);
    }

    /** Zakłada konto bez hasła i wysyła link aktywacyjny. */
    @Transactional
    public AppUser invite(String login, String displayName, String email, Role role, Set<Store> stores) {
        String username = AppUser.normalizeUsername(login);
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("Użytkownik o tym loginie już istnieje");
        }
        ensureEmailFree(email, null);
        AppUser user = AppUser.invited(username, displayName.trim(), email, role);
        user.setStores(stores);
        user = users.save(user);
        activationService.sendActivationLink(user);
        return user;
    }

    /**
     * Pierwszy wolny login: jkowalski, a przy tym samym jkowalski2, jkowalski3. Konta się nie usuwa, więc login osoby,
     * która odeszła, nie trafi do nikogo innego. Pusty, gdy z imienia i nazwiska nie da się złożyć loginu.
     */
    @Transactional(readOnly = true)
    public String suggestUsername(String displayName) {
        String base = LoginNames.base(displayName);
        if (base.isEmpty() || !users.existsByUsername(base)) {
            return base;
        }
        int number = 2;
        while (users.existsByUsername(base + number)) {
            number++;
        }
        return base + number;
    }

    /** Wysyła nowy link aktywacyjny (poprzednie przestają działać). */
    @Transactional
    public void resendActivationLink(UUID id) {
        AppUser user = users.findWithRoleById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        if (!user.isPendingActivation()) {
            throw ApiException.conflict("Konto jest już aktywne");
        }
        if (!user.isEnabled()) {
            throw ApiException.conflict("Konto jest zablokowane - odblokuj je przed wysłaniem linku");
        }
        if (user.getEmail() == null) {
            throw ApiException.conflict("Użytkownik nie ma adresu e-mail");
        }
        activationService.sendActivationLink(user);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, UUID actorId) {
        AppUser user = users.findWithRoleById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        String position = positionName(user.getId());

        boolean wantsOwner = request.owner() == null ? user.isOwner() : request.owner();
        boolean losesOwnerAccess = user.isOwner() && user.isEnabled() && (!wantsOwner || !request.enabled());
        if (losesOwnerAccess && users.countOtherActiveOwners(user.getId()) == 0) {
            throw ApiException.conflict("Musi pozostać co najmniej jeden aktywny właściciel");
        }

        updateProfile(user, request.displayName(), request.email());
        if (wantsOwner != user.isOwner()) {
            user.setRole(wantsOwner ? ownerRole() : accountRole());
        }
        user.setEnabled(request.enabled());
        if (request.storeIds() != null) {
            user.setStores(Set.copyOf(storeService.loadAll(request.storeIds())));
        }
        if (!user.isOwner() && (request.storeRoles() != null || request.storeIds() != null)) {
            applyRanks(user, user.getStores(), request.storeRoles(), positionRank(user.getId()), actorId);
        }
        if (request.canAssignTasks() != null) {
            user.setCanAssignTasks(request.canAssignTasks());
        }
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            user.resetPassword(passwordEncoder.encode(request.newPassword()));
            activationService.revokeLinks(user);
        }
        return userMapper.toResponse(user, position);
    }

    /**
     * Usunięcie konta przez właściciela: znacznik, blokada i wylogowanie, a konto znika z listy. Login zostaje zajęty,
     * adres e-mail się zwalnia. Nie usuwa się siebie, ostatniego aktywnego właściciela ani osoby, która pracuje
     * (najpierw koniec pracy w Pracownikach, bo grafik i czas pracy liczą się dalej).
     */
    @Transactional
    public void delete(UUID id, UUID byUserId) {
        AppUser user = users.findWithRoleById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        if (user.getDeletedAt() != null) {
            return;
        }
        if (user.getId().equals(byUserId)) {
            throw ApiException.conflict("Nie możesz usunąć własnego konta");
        }
        if (user.isOwner() && user.isEnabled() && users.countOtherActiveOwners(user.getId()) == 0) {
            throw ApiException.conflict("Musi pozostać co najmniej jeden aktywny właściciel");
        }
        String position = positionName(user.getId());
        if (position != null) {
            throw ApiException.conflict("To konto pracuje na stanowisku „" + position
                    + "”. Najpierw zakończ jego pracę w Pracownikach.");
        }
        user.markDeleted(byUserId);
        activationService.revokeLinks(user);
    }

    @Transactional
    public UserResponse restore(UUID id) {
        AppUser user = users.findWithRoleById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        if (user.getDeletedAt() != null) {
            String email = AppUser.normalizeEmail(user.getEmail());
            if (email != null && users.existsByEmailExcluding(email, user.getId())) {
                throw ApiException.conflict("Adres " + email + " ma już inne konto. Zmień go tam, żeby przywrócić to.");
            }
            user.restoreDeleted();
        }
        return userMapper.toResponse(user, positionName(user.getId()));
    }

    /** Imię i nazwisko oraz adres e-mail konta. */
    @Transactional
    public void updateProfile(AppUser user, String displayName, String requestedEmail) {
        String email = AppUser.normalizeEmail(requestedEmail);
        if (email == null && user.isPendingActivation()) {
            throw ApiException.badRequest("Konto czeka na aktywację - podaj adres e-mail, na który wyślemy link");
        }
        ensureEmailFree(email, user.getId());
        user.setDisplayName(displayName.trim());
        user.setEmail(email);
    }

    private String positionName(UUID userId) {
        return employees.findActivePositionNames().stream()
                .filter(row -> userId.equals(row[0]))
                .map(row -> (String) row[1])
                .findFirst().orElse(null);
    }

    private void ensureEmailFree(String email, UUID excludedId) {
        String normalized = AppUser.normalizeEmail(email);
        if (normalized != null && users.existsByEmailExcluding(normalized, excludedId)) {
            throw ApiException.conflict("Ten adres e-mail jest już przypisany do innego konta");
        }
    }

    private Role ownerRole() {
        return roles.findByOwnerTrue().orElseThrow(() -> new IllegalStateException("Brak roli właściciela"));
    }

    private Role accountRole() {
        return roles.findBySystemKey(Role.ACCOUNT_KEY)
                .orElseThrow(() -> new IllegalStateException("Brak roli Konto sklepu"));
    }

    /**
     * Ranga w każdym sklepie konta: wybrana w formularzu, a bez wyboru dotychczasowa albo Pracownik. Rangę w sklepie,
     * w którym konto pracuje na stanowisku, ustala stanowisko (zmiana = 409).
     */
    private void applyRanks(AppUser user, Set<Store> stores, Map<UUID, UUID> requested, PositionRank position,
            UUID actorId) {
        if (user.isOwner()) {
            return;
        }
        Map<UUID, UUID> wanted = requested == null ? Map.of() : requested;
        for (Store store : stores) {
            UUID roleId = wanted.get(store.getId());
            StoreRole current = user.rankIn(store.getId());
            StoreRole rank;
            if (roleId != null) {
                rank = ranks.active(store.getId(), roleId);
            } else if (current != null) {
                continue;
            } else {
                rank = ranks.lowest(store.getId());
                if (rank == null) {
                    continue;
                }
            }
            boolean changes = current == null || !current.getId().equals(rank.getId());
            if (roleId != null && changes && position != null && position.storeId().equals(store.getId())
                    && position.key() != null && !rank.getKey().equals(position.key())) {
                throw ApiException.conflict("Rangę tego konta w sklepie " + store.getNumber()
                        + " ustala stanowisko „" + position.name() + "”. Zmień je w Pracownikach.");
            }
            ranks.assign(user, rank, actorId);
        }
    }

    private record PositionRank(UUID storeId, String key, String name) {
    }

    private PositionRank positionRank(UUID userId) {
        return employees.findActiveOfUsers(List.of(userId)).stream().findFirst()
                .map(employee -> new PositionRank(employee.getStore().getId(), employee.getPosition().getRoleKey(),
                        employee.getPosition().getName()))
                .orElse(null);
    }
}
