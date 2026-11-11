package com.intermanage.backend.auth.dto;

import java.util.UUID;

/**
 * Zdjęcie w tle konta: id (do „Przywróć” i klucz pamięci podręcznej), adres pliku (CDN z R2 albo backend dla zdjęć
 * w bazie) i przesłonięcie zdjęcia pod treścią w procentach.
 */
public record BackgroundResponse(UUID id, String url, int veilPercent) {
}
