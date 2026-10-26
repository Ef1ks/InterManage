package com.intermanage.backend.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.dto.MeResponse;
import com.intermanage.backend.entity.AppUser;
import com.intermanage.backend.exception.ApiException;
import com.intermanage.backend.mapper.UserMapper;
import com.intermanage.backend.repository.AppUserRepository;
import com.intermanage.backend.security.AppUserPrincipal;

@Service
public class AuthService {

    public static final String NO_STORE_MESSAGE =
            "Nie masz przypisanego żadnego sklepu. Poproś właściciela o dostęp, a potem zaloguj się ponownie.";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ActivationService activationService;
    private final UserMapper userMapper;
    private final StoreService storeService;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, ActivationService activationService,
            UserMapper userMapper, StoreService storeService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.activationService = activationService;
        this.userMapper = userMapper;
        this.storeService = storeService;
    }

    /** Zużywa link aktywacyjny i zwraca principal do zalogowania (z obowiązkiem ustawienia hasła). */
    @Transactional
    public AppUserPrincipal activate(String token) {
        AppUser user = activationService.activate(token);
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        principal.eraseCredentials();
        return principal;
    }

    /**
     * Pierwsze własne hasło po aktywacji albo po haśle tymczasowym od właściciela. Nie wymaga obecnego hasła,
     * ale działa tylko, gdy zmiana jest wymagana. Zwraca nowy principal, dla którego trzeba wystawić nowy token.
     */
    @Transactional
    public AppUserPrincipal setUpPassword(UUID userId, String newPassword) {
        AppUser user = load(userId);
        if (!user.isPasswordChangeRequired()) {
            throw ApiException.conflict("Hasło jest już ustawione - zmień je w ustawieniach konta");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        activationService.revokeLinks(user);
        users.flush();
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        principal.eraseCredentials();
        return principal;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = load(userId);
        return userMapper.toMeResponse(user, storeService.accessibleStores(user));
    }

    /** Zmienia własne hasło i zwraca nowy principal, dla którego trzeba wystawić nowy token. */
    @Transactional
    public AppUserPrincipal changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        AppUser user = load(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Obecne hasło jest nieprawidłowe");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        users.flush();
        AppUserPrincipal principal = AppUserPrincipal.from(user);
        principal.eraseCredentials();
        return principal;
    }

    private AppUser load(UUID userId) {
        return users.findWithRoleById(userId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
    }
}
