package com.intermanage.backend.auth.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Blokada po bezczynności ({@code app.auth.idle-lock-after}, domyślnie 10 min): konto chronione PIN-em (albo hasłem)
 * po tym czasie bez ruchu użytkownika trafia do tła i wraca dopiero po PIN-ie, jak przy {@link WorkdayLock}.
 * <p>
 * Ruch myszy i klawiatury liczy przeglądarka (strona sama woła {@code POST /api/auth/lock}), bo odświeżanie danych
 * w tle też wysyła żądania. Backend pilnuje przypadku bez żadnych żądań (zamknięta karta, uśpiony komputer): token
 * jest odnawiany co {@code app.auth.token-refresh-after}, więc token starszy niż blokada plus odnowienie znaczy, że
 * przez cały ten czas z przeglądarki nic nie przyszło. Tylko przeglądarka (nie aplikacja mobilna). Pusta wartość
 * albo 0 wyłącza blokadę.
 */
@Component
public class IdleLock {

    private final Duration after;
    private final Duration refreshAfter;
    private final WorkdayLock workdayLock;

    public IdleLock(@Value("${app.auth.idle-lock-after:10m}") Duration after,
            @Value("${app.auth.token-refresh-after}") Duration refreshAfter, WorkdayLock workdayLock) {
        this.after = after == null || after.isZero() || after.isNegative() ? null : after;
        this.refreshAfter = refreshAfter;
        this.workdayLock = workdayLock;
    }

    /** Czy od ostatniego żądania (ostatniego odnowienia tokenu) minęło więcej niż blokada. */
    public boolean due(Jwt jwt, Instant now) {
        Instant issuedAt = jwt.getIssuedAt();
        return after != null && issuedAt != null && issuedAt.plus(after).plus(refreshAfter).isBefore(now);
    }

    /** Minuty bez ruchu, po których przeglądarka blokuje konto; {@code null} = konto bez blokady. */
    public Integer minutesFor(UUID userId) {
        if (after == null || !workdayLock.protects(userId)) {
            return null;
        }
        return (int) Math.max(1, after.toMinutes());
    }
}
