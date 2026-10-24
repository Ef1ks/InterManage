package com.intermanage.backend.user;

import java.time.Instant;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.ApiException;
import com.intermanage.backend.role.Role;
import com.intermanage.backend.role.RoleRepository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final ActivationService activationService;

    public UserService(AppUserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            ActivationService activationService) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.activationService = activationService;
    }

    public record CreateUserRequest(
            @NotBlank(message = "Podaj login")
            @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$",
                    message = "Login: 3-50 znaków, litery, cyfry, kropka, myślnik lub podkreślnik") String username,
            @NotBlank(message = "Podaj imię i nazwisko")
            @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
            @NotBlank(message = "Podaj adres e-mail")
            @Email(message = "Podaj poprawny adres e-mail")
            @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
            @NotNull(message = "Wybierz rolę") Long roleId) {
    }

    /** {@code newPassword} puste = bez zmiany hasła. Ustawione hasło jest tymczasowe - użytkownik musi je zmienić. */
    public record UpdateUserRequest(
            @NotBlank(message = "Podaj imię i nazwisko")
            @Size(max = 100, message = "Maksymalnie 100 znaków") String displayName,
            @Email(message = "Podaj poprawny adres e-mail")
            @Size(max = 255, message = "Maksymalnie 255 znaków") String email,
            @NotNull(message = "Wybierz rolę") Long roleId,
            boolean enabled,
            @Size(min = 8, max = 72, message = "Hasło musi mieć od 8 do 72 znaków") String newPassword) {
    }

    public record UserResponse(
            Long id,
            String username,
            String displayName,
            String email,
            Long roleId,
            String roleName,
            boolean owner,
            boolean enabled,
            /** Konto założone, ale pracownik jeszcze nie aktywował go linkiem z maila. */
            boolean pendingActivation,
            Instant createdAt) {

        static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                    user.getRole().getId(), user.getRole().getName(), user.getRole().isOwner(), user.isEnabled(),
                    user.isPendingActivation(), user.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAllWithRole().stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = AppUser.normalizeUsername(request.username());
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("Użytkownik o tym loginie już istnieje");
        }
        ensureEmailFree(request.email(), null);
        AppUser user = users.save(AppUser.invited(username, request.displayName().trim(), request.email(),
                loadRole(request.roleId())));
        activationService.sendActivationLink(user);
        return UserResponse.from(user);
    }

    /** Wysyła nowy link aktywacyjny (poprzednie przestają działać). */
    @Transactional
    public void resendActivationLink(Long id) {
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
    public UserResponse update(Long id, UpdateUserRequest request) {
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
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            user.resetPassword(passwordEncoder.encode(request.newPassword()));
            activationService.revokeLinks(user);
        }
        return UserResponse.from(user);
    }

    private void ensureEmailFree(String email, Long excludedId) {
        String normalized = AppUser.normalizeEmail(email);
        if (normalized != null && users.existsByEmailExcluding(normalized, excludedId)) {
            throw ApiException.conflict("Ten adres e-mail jest już przypisany do innego konta");
        }
    }

    private Role loadRole(Long roleId) {
        return roles.findById(roleId).orElseThrow(() -> ApiException.badRequest("Wybrana rola nie istnieje"));
    }
}
