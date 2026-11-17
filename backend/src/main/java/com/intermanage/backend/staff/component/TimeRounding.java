package com.intermanage.backend.staff.component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Zaokrąglanie odbić według zasad sklepu, w którym odbito kartę: wejście w górę, wyjście w dół do wielokrotności
 * {@code minutes} liczonej od północy (co 15 min: 7:52 → 8:00, 16:07 → 16:00). Liczy się tylko w kartach czasu pracy;
 * surowy czas odbicia zostaje w bazie i na ekranie. Sklep bez wpisu albo z 0 nie zaokrągla (czas obcięty do minut).
 */
public record TimeRounding(Map<UUID, Integer> minutesByStore) {

    public static final TimeRounding NONE = new TimeRounding(Map.of());

    public LocalDateTime entry(UUID storeId, LocalDateTime at) {
        return up(at, minutesByStore.getOrDefault(storeId, 0));
    }

    public LocalDateTime exit(UUID storeId, LocalDateTime at) {
        return down(at, minutesByStore.getOrDefault(storeId, 0));
    }

    /** W górę do pełnych {@code minutes}; czas już równy wielokrotności zostaje. */
    public static LocalDateTime up(LocalDateTime at, int minutes) {
        LocalDateTime down = down(at, minutes);
        return down.equals(at.truncatedTo(ChronoUnit.MINUTES)) ? down : down.plusMinutes(minutes);
    }

    /** W dół do pełnych {@code minutes}. */
    public static LocalDateTime down(LocalDateTime at, int minutes) {
        LocalDateTime truncated = at.truncatedTo(ChronoUnit.MINUTES);
        if (minutes <= 1) {
            return truncated;
        }
        int ofDay = truncated.getHour() * 60 + truncated.getMinute();
        return truncated.minusMinutes(ofDay % minutes);
    }
}
