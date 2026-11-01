package com.intermanage.backend.backup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** {@code password} puste = hasło bez zmian. */
public record BackupSettingsRequest(
        boolean enabled,
        @Min(value = 0, message = "Godzina od 0 do 23") @Max(value = 23, message = "Godzina od 0 do 23") int runHour,
        @Min(value = 1, message = "Co najmniej 1 kopia") @Max(value = 365, message = "Najwyżej 365 kopii") int keepCount,
        @Size(min = 12, max = 200, message = "Hasło musi mieć od 12 do 200 znaków") String password) {
}
