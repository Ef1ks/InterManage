package com.intermanage.backend.staff.dto;

import java.time.DayOfWeek;

/** Godziny jako GG:MM; koniec przed początkiem to zmiana przez północ. */
public record ShiftResponse(DayOfWeek dayOfWeek, String start, String end) {
}
