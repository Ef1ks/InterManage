package com.intermanage.backend.auth.security;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.intermanage.backend.common.component.StoreTime;

/**
 * Blokada konta po pracy: konto otwarte w przeglądarce przed godziną {@code app.auth.workday-lock-at} (czas sklepu,
 * domyślnie 17:00) po tej godzinie przechodzi do tła i wraca dopiero po PIN-ie (albo haśle), więc wychodząc z pracy
 * nie trzeba się wylogowywać. Konto otwarte po tej godzinie blokuje się dopiero następnego dnia o tej samej porze.
 * Dotyczy tylko przeglądarki (nie aplikacji mobilnej) i tylko kont, które jej nie wyłączyły ({@link AutoLogout}).
 * Pusta wartość wyłącza blokadę.
 */
@Component
public class WorkdayLock {

    private final LocalTime lockAt;

    public WorkdayLock(@Value("${app.auth.workday-lock-at:17:00}") String lockAt) {
        this.lockAt = lockAt == null || lockAt.isBlank() ? null : LocalTime.parse(lockAt.trim());
    }

    /** Godzina blokady (czas sklepu), {@code null} = bez blokady. */
    public LocalTime lockAt() {
        return lockAt;
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
}
