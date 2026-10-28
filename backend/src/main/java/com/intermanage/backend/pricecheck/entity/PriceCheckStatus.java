package com.intermanage.backend.pricecheck.entity;

/**
 * Status wyjaśniania przyjęcia z ceną wyższą niż baza. Brak wpisu oznacza "do wyjaśnienia". Nazwa stałej trafia do
 * bazy, więc istniejących nie zmieniamy.
 */
public enum PriceCheckStatus {
    /** Sprawa zgłoszona dostawcy, czekamy na odpowiedź albo korektę. */
    REPORTED,
    /** Wyjaśnione: dostawca wystawił korektę albo cena została zaakceptowana. */
    RESOLVED
}
