package com.intermanage.backend.auth.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Blokada po bezczynności: konto chronione PIN-em (albo hasłem) po czasie ustawionym na koncie ({@link AutoLogout})
 * bez ruchu użytkownika trafia do tła i wraca dopiero po PIN-ie, jak przy {@link WorkdayLock}.
 * <p>
 * Ruch myszy i klawiatury liczy przeglądarka (strona sama woła {@code POST /api/auth/lock}), bo odświeżanie danych
 * w tle też wysyła żądania. Backend pilnuje przypadku bez żadnych żądań (zamknięta karta, uśpiony komputer): token
 * jest odnawiany co {@code app.auth.token-refresh-after}, więc token starszy niż blokada plus odnowienie znaczy, że
 * przez cały ten czas z przeglądarki nic nie przyszło. Tylko przeglądarka (nie aplikacja mobilna).
 */
@Component
public class IdleLock {

    private final Duration refreshAfter;

    public IdleLock(@Value("${app.auth.token-refresh-after}") Duration refreshAfter) {
        this.refreshAfter = refreshAfter;
    }

    /** Token starszy niż odnowienie: dopiero wtedy warto pytać bazę o ustawienia konta, nie przy każdym żądaniu. */
    public boolean mayBeDue(Jwt jwt, Instant now) {
        return due(jwt, Duration.ZERO, now);
    }

    /** Czy od ostatniego żądania (ostatniego odnowienia tokenu) minęło więcej niż blokada konta ({@code null} = bez niej). */
    public boolean due(Jwt jwt, Duration after, Instant now) {
        Instant issuedAt = jwt.getIssuedAt();
        return after != null && issuedAt != null && issuedAt.plus(after).plus(refreshAfter).isBefore(now);
    }
}
