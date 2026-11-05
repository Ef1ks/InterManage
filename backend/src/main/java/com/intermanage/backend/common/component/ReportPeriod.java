package com.intermanage.backend.common.component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.intermanage.backend.common.exception.ApiException;

/** Okres raportu podany przez użytkownika: oba końce włącznie, ograniczona liczba dni. */
public final class ReportPeriod {

    /** Najdłuższy okres raportu sprzedaży, także w {@code features/sales/periods.ts}. */
    public static final int MAX_DAYS = 366;

    private ReportPeriod() {
    }

    public static void check(LocalDate from, LocalDate to) {
        check(from, to, MAX_DAYS, "Okres");
    }

    public static void check(LocalDate from, LocalDate to, int maxDays) {
        check(from, to, maxDays, "Okres");
    }

    /** 400, gdy okres jest odwrócony albo dłuższy niż {@code maxDays}; {@code label} nazywa okres w komunikacie. */
    public static void check(LocalDate from, LocalDate to, int maxDays, String label) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest(label + ": data „od” jest późniejsza niż „do”");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw ApiException.badRequest(label + " może mieć najwyżej " + maxDays + " dni");
        }
    }
}
