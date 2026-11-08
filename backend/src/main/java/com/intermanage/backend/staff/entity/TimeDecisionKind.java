package com.intermanage.backend.staff.entity;

/**
 * Decyzja o odcinku: nadgodziny na polecenie pracodawcy, na wniosek pracownika (oba liczą się do czasu pracy i mają
 * wydruk), odrzucone (praca z własnej inicjatywy, czas jak w grafiku), usprawiedliwiony brak albo zrównoważenie dnia
 * ({@code OFFSET}: brak odrobiony czasem poza grafikiem). Nazwa trafia do bazy.
 */
public enum TimeDecisionKind {

    EMPLOYER_ORDER,
    EMPLOYEE_REQUEST,
    REJECTED,
    JUSTIFIED,
    OFFSET
}
