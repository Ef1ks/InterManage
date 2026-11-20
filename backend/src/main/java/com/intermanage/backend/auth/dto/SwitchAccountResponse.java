package com.intermanage.backend.auth.dto;

import java.util.UUID;

import com.intermanage.backend.auth.entity.SwitchUnlock;

/**
 * Konto zalogowane w tle w tej przeglądarce i to, czego wymaga przejście na nie. {@code pinLength} (tylko przy
 * PIN-ie, puste dla PIN-ów sprzed V168 do pierwszego udanego przejścia) pozwala przełączyć konto zaraz po wpisaniu
 * ostatniej cyfry.
 */
public record SwitchAccountResponse(UUID id, String displayName, String username, AvatarResponse avatar,
        SwitchUnlock unlock, Integer pinLength) {
}
