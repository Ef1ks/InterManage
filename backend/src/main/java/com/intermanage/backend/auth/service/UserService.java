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

    public UserService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            ActivationService activationService, UserMapper userMapper, StoreService storeService,
            StaffEmployeeRepository employees) {
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
                .map(user -> userMapper.toResponse(user, positions.get(user.getId())))
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        AppUser user = invite(request.username(), request.displayName(), request.email(), loadRole(request.roleId()),
                Set.copyOf(storeService.loadAll(request.storeIds())));
        return userMapper.toResponse(user, null);
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
    public UserResponse update(UUID id, UpdateUserRequest request) {
        AppUser user = users.findWithRoleById(id).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
        Role newRole = loadRole(request.roleId());
        String position = positionName(user.getId());
        if (position != null && !user.getRole().isOwner() && !newRole.getId().equals(user.getRole().getId())) {
            throw ApiException.conflict("Rolę tego konta ustala stanowisko „" + position + "”. Zmień je w Pracownikach.");
        }

        boolean losesOwnerAccess = user.getRole().isOwner() && user.isEnabled()
                && (!newRole.isOwner() || !request.enabled());
        if (losesOwnerAccess && users.countOtherActiveOwners(user.getId()) == 0) {
            throw ApiException.conflict("Musi pozostać co najmniej jeden aktywny właściciel");
        }

        updateProfile(user, request.displayName(), request.email());
        user.setRole(newRole);
        user.setEnabled(request.enabled());
        if (request.storeIds() != null) {
            user.setStores(Set.copyOf(storeService.loadAll(request.storeIds())));
        }
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            user.resetPassword(passwordEncoder.encode(request.newPassword()));
            activationService.revokeLinks(user);
        }
        return userMapper.toResponse(user, position);
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

    private Role loadRole(UUID roleId) {
        return roles.findById(roleId).orElseThrow(() -> ApiException.badRequest("Wybrana rola nie istnieje"));
    }
}
