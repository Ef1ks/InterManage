package com.intermanage.backend.auth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.dto.MeResponse;
import com.intermanage.backend.auth.dto.PinSettingsResponse;
import com.intermanage.backend.auth.dto.SwitchAccountRequest;
import com.intermanage.backend.auth.dto.SwitchAccountResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.SwitchUnlock;
import com.intermanage.backend.auth.entity.UserSwitchSettings;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.UserSwitchSettingsRepository;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.auth.security.BackgroundAccounts;
import com.intermanage.backend.auth.security.BackgroundAccounts.Account;
import com.intermanage.backend.auth.security.JwtService;
import com.intermanage.backend.common.exception.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Przełączanie kont w przeglądarce: kilka kont zalogowanych naraz, jedno aktywne ({@value JwtService#COOKIE_NAME}),
 * pozostałe w tle ({@value JwtService#ACCOUNTS_COOKIE_NAME}). Przejście na konto w tle wymaga jego PIN-u, chyba że
 * właściciel konta to wyłączył; bez PIN-u (nieustawiony albo zablokowany po {@value #MAX_PIN_ATTEMPTS} błędnych
 * próbach) wymaga hasła tego konta. Aktywne konto przy przełączeniu trafia do tła.
 */
@Service
public class AccountSwitchService {

    static final int MAX_PIN_ATTEMPTS = 5;

    private final BackgroundAccounts backgroundAccounts;
    private final JwtService jwtService;
    private final AuthService authService;
    private final UserAvatarService avatarService;
    private final UserSwitchSettingsRepository settings;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AccountSwitchService(BackgroundAccounts backgroundAccounts, JwtService jwtService, AuthService authService,
            UserAvatarService avatarService, UserSwitchSettingsRepository settings, AppUserRepository users,
            PasswordEncoder passwordEncoder) {
        this.backgroundAccounts = backgroundAccounts;
        this.jwtService = jwtService;
        this.authService = authService;
        this.avatarService = avatarService;
        this.settings = settings;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Konta w tle; wygasłe i wylogowane (blokada, zmiana hasła) znikają też z ciasteczka. */
    @Transactional(readOnly = true)
    public List<SwitchAccountResponse> list(HttpServletRequest request, HttpServletResponse response,
            AppUserPrincipal active) {
        List<Account> accounts = backgroundAccounts.read(request, idOf(active));
        if (accounts.size() != JwtService.accountTokens(request).size()) {
            backgroundAccounts.write(response, accounts);
        }
        return accounts.stream().map(account -> toResponse(account.principal().getId())).toList();
    }

    /** Loguje kolejne konto hasłem: ono staje się aktywne, a dotychczasowe zostaje zalogowane w tle. */
    @Transactional(readOnly = true)
    public MeResponse add(HttpServletRequest request, HttpServletResponse response, AppUserPrincipal active,
            AppUserPrincipal added, boolean rememberMe) {
        if (added.getId().equals(active.getId())) {
            throw ApiException.conflict("Jesteś już zalogowany na to konto");
        }
        List<Account> others = new ArrayList<>(backgroundAccounts.read(request, active.getId()).stream()
                .filter(account -> !account.principal().getId().equals(added.getId()))
                .toList());
        others.add(backgroundAccounts.fresh(active, jwtService.rememberMe(request)));
        if (others.size() > BackgroundAccounts.MAX) {
            throw ApiException.conflict("W tle może być najwyżej " + BackgroundAccounts.MAX
                    + " kont. Wyloguj któreś z nich, zanim dodasz kolejne.");
        }
        backgroundAccounts.write(response, others);
        jwtService.writeTokenCookie(response, added, rememberMe);
        return authService.me(added.getId());
    }

    /**
     * Przejście na konto w tle. Zapisuje błędne próby PIN-u także wtedy, gdy kończy się błędem (stąd
     * {@code noRollbackFor}).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public MeResponse switchTo(HttpServletRequest request, HttpServletResponse response, AppUserPrincipal active,
            UUID userId, SwitchAccountRequest unlock) {
        List<Account> accounts = backgroundAccounts.read(request, idOf(active));
        Account target = accounts.stream()
                .filter(account -> account.principal().getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "To konto nie jest już zalogowane w tej przeglądarce. Zaloguj się na nie hasłem."));

        verify(userId, unlock == null ? new SwitchAccountRequest(null, null) : unlock);

        List<Account> others = new ArrayList<>(accounts.stream().filter(account -> account != target).toList());
        if (active != null) {
            others.add(backgroundAccounts.fresh(active, jwtService.rememberMe(request)));
        }
        backgroundAccounts.write(response, others);
        jwtService.writeTokenCookie(response, target.principal(), JwtService.rememberMe(target.token()));
        return authService.me(userId);
    }

    /** Wylogowuje jedno konto w tle (aktywne zostaje). */
    @Transactional(readOnly = true)
    public void remove(HttpServletRequest request, HttpServletResponse response, AppUserPrincipal active,
            UUID userId) {
        backgroundAccounts.write(response, backgroundAccounts.read(request, idOf(active)).stream()
                .filter(account -> !account.principal().getId().equals(userId))
                .toList());
    }

    /** Wylogowuje wszystkie konta w tej przeglądarce. */
    public void logoutAll(HttpServletResponse response) {
        jwtService.clearTokenCookie(response);
        jwtService.clearAccountsCookie(response);
    }

    /** Po zalogowaniu hasłem: to konto nie jest już w tle (byłoby na liście drugi raz). */
    @Transactional(readOnly = true)
    public void dropFromBackground(HttpServletRequest request, HttpServletResponse response, UUID userId) {
        if (!JwtService.accountTokens(request).isEmpty()) {
            backgroundAccounts.write(response, backgroundAccounts.read(request, userId));
        }
    }

    @Transactional(readOnly = true)
    public PinSettingsResponse pinSettings(UUID userId) {
        return settings.findOfUser(userId)
                .map(s -> new PinSettingsResponse(s.hasPin(), s.isPinRequired()))
                .orElseGet(() -> new PinSettingsResponse(false, true));
    }

    /** Ustawia albo zmienia własny PIN; wymaga hasła, żeby nie ustawił go ktoś przy pozostawionym koncie. */
    @Transactional
    public PinSettingsResponse setPin(UUID userId, String currentPassword, String pin) {
        AppUser user = loadUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Obecne hasło jest nieprawidłowe");
        }
        UserSwitchSettings own = settingsOf(user);
        own.changePin(passwordEncoder.encode(pin));
        return new PinSettingsResponse(true, own.isPinRequired());
    }

    @Transactional
    public PinSettingsResponse setPinRequired(UUID userId, boolean required) {
        UserSwitchSettings own = settingsOf(loadUser(userId));
        own.setPinRequired(required);
        return new PinSettingsResponse(own.hasPin(), required);
    }

    private void verify(UUID userId, SwitchAccountRequest unlock) {
        UserSwitchSettings own = settings.findOfUser(userId).orElse(null);
        switch (unlockOf(own)) {
            case NONE -> {
            }
            case PIN -> {
                if (unlock.pin() == null || unlock.pin().isBlank()) {
                    throw ApiException.badRequest("Podaj PIN");
                }
                if (passwordEncoder.matches(unlock.pin(), own.getPinHash())) {
                    own.resetAttempts();
                    return;
                }
                own.failedAttempt();
                int left = MAX_PIN_ATTEMPTS - own.getFailedAttempts();
                throw ApiException.badRequest(left > 0
                        ? "Nieprawidłowy PIN. " + (left == 1 ? "Została 1 próba." : "Zostało prób: " + left + ".")
                        : "Za dużo błędnych prób. Podaj hasło tego konta, PIN zadziała znowu po zalogowaniu.")
                        .withProperty("unlock", unlockOf(own));
            }
            case PASSWORD -> {
                if (unlock.password() == null || unlock.password().isBlank()) {
                    throw ApiException.badRequest("Podaj hasło");
                }
                if (!passwordEncoder.matches(unlock.password(), loadUser(userId).getPasswordHash())) {
                    throw ApiException.badRequest("Nieprawidłowe hasło");
                }
                if (own != null) {
                    own.resetAttempts();
                }
            }
        }
    }

    private static SwitchUnlock unlockOf(UserSwitchSettings own) {
        if (own != null && !own.isPinRequired()) {
            return SwitchUnlock.NONE;
        }
        if (own != null && own.hasPin() && own.getFailedAttempts() < MAX_PIN_ATTEMPTS) {
            return SwitchUnlock.PIN;
        }
        return SwitchUnlock.PASSWORD;
    }

    private SwitchAccountResponse toResponse(UUID userId) {
        AppUser user = loadUser(userId);
        return new SwitchAccountResponse(user.getId(), user.getDisplayName(), user.getUsername(),
                avatarService.current(userId), unlockOf(settings.findOfUser(userId).orElse(null)));
    }

    private UserSwitchSettings settingsOf(AppUser user) {
        return settings.findOfUser(user.getId()).orElseGet(() -> settings.save(new UserSwitchSettings(user)));
    }

    private AppUser loadUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
    }

    private static UUID idOf(AppUserPrincipal principal) {
        return principal == null ? null : principal.getId();
    }
}
