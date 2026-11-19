package com.intermanage.backend.activity.entity;

/** Rodzaj wpisu w dzienniku sklepu. Nazwa stałej trafia do bazy ({@code store_events.kind}). */
public enum StoreEventKind {
    /** Udana zmiana przez API (dodanie, zmiana, usunięcie, przywrócenie…). */
    ACTION,
    /** Zmiana odrzucona przez serwer: brak uprawnień, konflikt, błąd walidacji albo błąd serwera. */
    FAILED,
    /** Ostrzeżenie albo błąd z logów backendu (SLF4J). */
    SYSTEM
}
