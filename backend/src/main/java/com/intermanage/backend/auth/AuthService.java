package com.intermanage.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.ApiException;
import com.intermanage.backend.security.AppUserPrincipal;
import com.intermanage.backend.user.AppUser;
import com.intermanage.backend.user.ActivationService;
import com.intermanage.backend.user.AppUserRepository;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ActivationService activationService;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, ActivationService activationService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.activationService = activationService;
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
    public AppUserPrincipal setUpPassword(Long userId, String newPassword) {
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
    public MeResponse me(Long userId) {
        return MeResponse.from(load(userId));
    }

    /** Zmienia własne hasło i zwraca nowy principal, dla którego trzeba wystawić nowy token. */
    @Transactional
    public AppUserPrincipal changeOwnPassword(Long userId, String currentPassword, String newPassword) {
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

    private AppUser load(Long userId) {
        return users.findWithRoleById(userId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
    }
}
