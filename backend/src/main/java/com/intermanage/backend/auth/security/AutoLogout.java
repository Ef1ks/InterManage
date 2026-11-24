package com.intermanage.backend.auth.security;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.entity.UserSwitchSettings;
import com.intermanage.backend.auth.repository.UserSwitchSettingsRepository;

/**
 * Automatyczne wylogowywanie konta według jego własnych ustawień (Moje konto): po bezczynności ({@link IdleLock}) i po
 * godzinie końca pracy ({@link WorkdayLock}). Konto bez własnych ustawień ma domyślny czas aplikacji
 * ({@code app.auth.idle-lock-after}, puste albo 0 = bez blokady po bezczynności) i blokadę po pracy.
 * Konto, którego właściciel wyłączył PIN, nie jest wylogowywane wcale, bo i tak otwiera się jednym kliknięciem.
 */
@Component
public class AutoLogout {

    /** Krócej backend mógłby pomylić z chwilą bez żądań (token odnawia się co {@code app.auth.token-refresh-after}). */
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 480;

    /** Co obowiązuje konto: {@code idle} puste = bez blokady po bezczynności. */
    public record Rules(boolean pinProtected, Duration idle, boolean workday) {
    }

    private final Duration defaultIdle;
    private final UserSwitchSettingsRepository settings;
    private final TransactionTemplate readOnlyTx;

    public AutoLogout(@Value("${app.auth.idle-lock-after:10m}") Duration defaultIdle,
            UserSwitchSettingsRepository settings, PlatformTransactionManager transactionManager) {
        this.defaultIdle = defaultIdle == null || defaultIdle.isZero() || defaultIdle.isNegative() ? null : defaultIdle;
        this.settings = settings;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public Rules rules(UUID userId) {
        return readOnlyTx.execute(status -> settings.findOfUser(userId)
                .map(this::rules)
                .orElseGet(() -> new Rules(true, defaultIdle, true)));
    }

    /** Minuty bez ruchu, po których przeglądarka wylogowuje konto; {@code null} = nie wylogowuje. */
    public Integer idleMinutes(UUID userId) {
        return minutes(rules(userId).idle());
    }

    /** Czas ustawiony na koncie, bez niego domyślny; także przy wyłączonym wylogowywaniu. */
    public Integer chosenMinutes(UserSwitchSettings own) {
        return own != null && own.getIdleLockMinutes() != null ? own.getIdleLockMinutes() : minutes(defaultIdle);
    }

    private Rules rules(UserSwitchSettings own) {
        if (!own.isPinRequired() || !own.isAutoLogout()) {
            return new Rules(own.isPinRequired(), null, false);
        }
        Duration idle = own.getIdleLockMinutes() == null ? defaultIdle : Duration.ofMinutes(own.getIdleLockMinutes());
        return new Rules(true, idle, own.isWorkdayLock());
    }

    private static Integer minutes(Duration idle) {
        return idle == null ? null : (int) Math.max(1, idle.toMinutes());
    }
}
