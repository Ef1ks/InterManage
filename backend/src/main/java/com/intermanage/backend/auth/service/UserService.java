package com.intermanage.backend.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.CreateUserRequest;
import com.intermanage.backend.dto.UpdateUserRequest;
import com.intermanage.backend.dto.UserResponse;
import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.entity.Role;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.UserMapper;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.repository.RoleRepository;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final ActivationService activationService;
    private final UserMapper userMapper;
    private final StoreService storeService;

    public UserService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            ActivationService activationService, UserMapper userMapper, StoreService storeService) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.activationService = activationService;
        this.userMapper = userMapper;
        this.storeService = storeService;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAllWithRole().stream().map(userMapper::toResponse).toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = AppUser.normalizeUsername(request.username());
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("Użytkownik o tym loginie już istnieje");
        }
        ensureEmailFree(request.email(), null);
        AppUser user = AppUser.invited(username, request.displayName().trim(), request.email(),
                loadRole(request.roleId()));
        user.setStores(Set.copyOf(storeService.loadAll(request.storeIds())));
        user = users.save(user);
        activationService.sendActivationLink(user);
        return userMapper.toResponse(user);
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

        boolean losesOwnerAccess = user.getRole().isOwner() && user.isEnabled()
                && (!newRole.isOwner() || !request.enabled());
        if (losesOwnerAccess && users.countOtherActiveOwners(user.getId()) == 0) {
            throw ApiException.conflict("Musi pozostać co najmniej jeden aktywny właściciel");
        }

        String email = AppUser.normalizeEmail(request.email());
        if (email == null && user.isPendingActivation()) {
            throw ApiException.badRequest("Konto czeka na aktywację - podaj adres e-mail, na który wyślemy link");
        }
        ensureEmailFree(email, user.getId());

        user.setDisplayName(request.displayName().trim());
        user.setEmail(email);
        user.setRole(newRole);
        user.setEnabled(request.enabled());
        if (request.storeIds() != null) {
            user.setStores(Set.copyOf(storeService.loadAll(request.storeIds())));
        }
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            user.resetPassword(passwordEncoder.encode(request.newPassword()));
            activationService.revokeLinks(user);
        }
        return userMapper.toResponse(user);
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
