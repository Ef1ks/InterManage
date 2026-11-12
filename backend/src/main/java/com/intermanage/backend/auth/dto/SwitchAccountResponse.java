package com.intermanage.backend.auth.dto;

import java.util.UUID;

import com.intermanage.backend.auth.entity.SwitchUnlock;

/** Konto zalogowane w tle w tej przeglądarce i to, czego wymaga przejście na nie. */
public record SwitchAccountResponse(UUID id, String displayName, String username, AvatarResponse avatar,
        SwitchUnlock unlock) {
}
