package com.intermanage.backend.staff.entity;

/** Za co przysługuje dzień wolny w grafiku. Nazwa stałej trafia do bazy i na ekran. */
public enum ScheduleDayOffKind {

    /** Za pracę w niedzielę albo święto (art. 151(11) KP). */
    WN,

    /** Z tytułu przeciętnie pięciodniowego tygodnia pracy (art. 129 § 1 KP). */
    W5,

    /**
     * Zwykłe „wolne”, żeby pracownik widział, że tego dnia nie pracuje (np. w niedzielę). Bez skutków w przepisach:
     * nie rozlicza niedzieli ani święta i nie jest liczone jak W5.
     */
    W
}
