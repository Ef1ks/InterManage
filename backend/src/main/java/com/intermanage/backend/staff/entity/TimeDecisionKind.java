package com.intermanage.backend.staff.entity;

/**
 * Decyzja o odcinku: nadgodziny na polecenie pracodawcy, na wniosek pracownika (oba liczą się do czasu pracy i mają
 * wydruk), odrzucone (praca z własnej inicjatywy, czas jak w grafiku), usprawiedliwiony brak albo zrównoważenie dnia
 * ({@code OFFSET}: brak odrobiony czasem poza grafikiem). Do tego głównie z wniosków pracowników: {@code WORKED_OFF}
 * odpracowanie wyjścia prywatnego (praca, ale nie nadgodziny, art. 151²¹ § 2 KP), {@code PRIVATE_EXIT} wyjście prywatne
 * do odpracowania (brak bez wynagrodzenia, dalej do odrobienia w okresie), {@code JUSTIFIED_HALF} usprawiedliwiony brak
 * płatny w 50% (siła wyższa, art. 148¹ KP) i {@code JUSTIFIED_UNPAID} usprawiedliwiony bez wynagrodzenia. Nazwa trafia
 * do bazy.
 */
public enum TimeDecisionKind {

    EMPLOYER_ORDER,
    EMPLOYEE_REQUEST,
    REJECTED,
    JUSTIFIED,
    OFFSET,
    WORKED_OFF,
    PRIVATE_EXIT,
    JUSTIFIED_HALF,
    JUSTIFIED_UNPAID;

    /** Decyzja o czasie poza grafikiem. */
    public boolean aboutOvertime() {
        return this == EMPLOYER_ORDER || this == EMPLOYEE_REQUEST || this == REJECTED || this == WORKED_OFF;
    }

    /** Decyzja o braku czasu z grafiku. */
    public boolean aboutShortfall() {
        return this == JUSTIFIED || this == PRIVATE_EXIT || this == JUSTIFIED_HALF || this == JUSTIFIED_UNPAID;
    }
}
