package com.intermanage.backend.staff.entity;

/** Kto chce czasu wolnego za nadgodziny (art. 151² KP). Nazwa stałej trafia do bazy. */
public enum TimeOffInitiative {

    /** Na wniosek pracownika: godzina wolnego za godzinę nadliczbową (§ 1). */
    EMPLOYEE(1.0),
    /** Z inicjatywy pracodawcy: półtorej godziny wolnego za godzinę, do końca okresu (§ 2). */
    EMPLOYER(1.5);

    private final double ratio;

    TimeOffInitiative(double ratio) {
        this.ratio = ratio;
    }

    /** Ile minut nadgodzin rozlicza tyle minut czasu wolnego. */
    public int compensates(int minutes) {
        return (int) Math.floor(minutes / ratio);
    }
}
