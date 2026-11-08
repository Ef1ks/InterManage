package com.intermanage.backend.staff.entity;

/**
 * Odcinek karty, o którym decyduje kierownik: czas poza grafikiem albo brak czasu z grafiku. {@code BALANCE} to decyzja
 * o całym dniu: brak i czas poza grafikiem znoszą się (np. przyszedł godzinę później i został godzinę dłużej). Nazwa
 * trafia do bazy.
 */
public enum TimeSegmentKind {

    OVERTIME,
    SHORTFALL,
    BALANCE
}
