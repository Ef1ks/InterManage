package com.intermanage.backend.staff.entity;

/**
 * Rodzaj nieobecności z limitem roczny w dniach, gdy ustawa go podaje. Nazwa stałej trafia do bazy. Urlop na żądanie
 * jest częścią urlopu wypoczynkowego, więc też zmniejsza pulę ({@link #countsAsAnnual()}).
 */
public enum LeaveKind {

    ANNUAL(null, true),
    ON_DEMAND(4, true),
    CHILD_CARE(2, false),
    FORCE_MAJEURE(2, false),
    CARE(5, false),
    REHABILITATION(21, false),
    OCCASIONAL(null, false),
    UNPAID(null, false),

    /** Płatna przerwa przy umowie B2B: limit dni bierze się z umowy ({@code StaffContract.paidLeaveDays}), nie z ustawy. */
    B2B_PAID(null, false),

    /**
     * Zwolnienie lekarskie (L4): dni kalendarzowe niezdolności do pracy, bez limitu. Przysługuje też przy zleceniu z
     * dobrowolnym chorobowym, więc nie wymaga umowy o pracę.
     */
    SICK(null, false);

    private final Integer limitDays;
    private final boolean countsAsAnnual;

    LeaveKind(Integer limitDays, boolean countsAsAnnual) {
        this.limitDays = limitDays;
        this.countsAsAnnual = countsAsAnnual;
    }

    public Integer limitDays() {
        return limitDays;
    }

    /** L4 liczy dni kalendarzowe (wynagrodzenie chorobowe za 33 dni w roku, art. 92 KP), urlopy dni pracy z grafiku. */
    public boolean calendarDays() {
        return this == SICK;
    }

    public boolean countsAsAnnual() {
        return countsAsAnnual;
    }
}
