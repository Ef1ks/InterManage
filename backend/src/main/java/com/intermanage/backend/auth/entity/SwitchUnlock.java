package com.intermanage.backend.auth.entity;

/** Czego wymaga przejście na konto zalogowane w tle (nie trafia do bazy). */
public enum SwitchUnlock {
    /** Właściciel konta wyłączył PIN przy przełączaniu. */
    NONE,
    PIN,
    /** PIN nieustawiony albo zablokowany po błędnych próbach. */
    PASSWORD
}
