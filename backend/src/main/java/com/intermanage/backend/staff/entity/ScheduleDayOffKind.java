package com.intermanage.backend.staff.entity;

/** Za co przysługuje dzień wolny w grafiku. Nazwa stałej trafia do bazy i na ekran. */
public enum ScheduleDayOffKind {

    /** Za pracę w niedzielę albo święto (art. 151(11) KP). */
    WN,

    /** Z tytułu przeciętnie pięciodniowego tygodnia pracy (art. 129 § 1 KP). */
    W5
}
