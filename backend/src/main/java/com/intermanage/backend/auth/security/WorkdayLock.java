package com.intermanage.backend.auth.security;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.intermanage.backend.auth.entity.UserSwitchSettings;
import com.intermanage.backend.auth.repository.UserSwitchSettingsRepository;
import com.intermanage.backend.common.component.StoreTime;

/**
 * Blokada konta po pracy: konto otwarte w przeglądarce przed godziną {@code app.auth.workday-lock-at} (czas sklepu,
 * domyślnie 17:00) po tej godzinie przechodzi do tła i wraca dopiero po PIN-ie (albo haśle), więc wychodząc z pracy
 * nie trzeba się wylogowywać. Konto otwarte po tej godzinie blokuje się dopiero następnego dnia o tej samej porze.
 * Dotyczy tylko przeglądarki (nie aplikacji mobilnej) i tylko kont chronionych: konto, którego właściciel wyłączył
 * PIN, i tak otwiera się jednym kliknięciem. Pusta wartość wyłącza blokadę.
 */
@Component
public class WorkdayLock {

    private final LocalTime lockAt;
    private final UserSwitchSettingsRepository settings;
    private final TransactionTemplate readOnlyTx;

    public WorkdayLock(@Value("${app.auth.workday-lock-at:17:00}") String lockAt,
            UserSwitchSettingsRepository settings, PlatformTransactionManager transactionManager) {
        this.lockAt = lockAt == null || lockAt.isBlank() ? null : LocalTime.parse(lockAt.trim());
        this.settings = settings;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** Czy od otwarcia konta minęła godzina blokady. */
    public boolean due(Jwt jwt, Instant now) {
        return lockAt != null && JwtService.unlockedAt(jwt).isBefore(lastLock(now));
    }

    /** Ostatnia godzina blokady nie późniejsza niż {@code now}: dziś, a przed tą godziną wczoraj. */
    Instant lastLock(Instant now) {
        ZonedDateTime local = now.atZone(StoreTime.ZONE);
        ZonedDateTime today = local.toLocalDate().atTime(lockAt).atZone(StoreTime.ZONE);
        return (today.isAfter(local) ? today.minusDays(1) : today).toInstant();
    }

    /** Czy przejście na konto wymaga PIN-u albo hasła (bez ustawień: hasła). */
    public boolean protects(UUID userId) {
        return Boolean.TRUE.equals(readOnlyTx.execute(status -> settings.findOfUser(userId)
                .map(UserSwitchSettings::isPinRequired)
                .orElse(true)));
    }
}
