package com.intermanage.backend.auth.dto;

import java.util.UUID;

/** Zdjęcie profilowe konta: id (do „Przywróć”) i adres pliku (CDN z R2 albo backend dla zdjęć w bazie). */
public record AvatarResponse(UUID id, String url) {
}
