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
    B2B_PAID(null, false);

    private final Integer limitDays;
    private final boolean countsAsAnnual;

    LeaveKind(Integer limitDays, boolean countsAsAnnual) {
        this.limitDays = limitDays;
        this.countsAsAnnual = countsAsAnnual;
    }

    public Integer limitDays() {
        return limitDays;
    }

    public boolean countsAsAnnual() {
        return countsAsAnnual;
    }
}
