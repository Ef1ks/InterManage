package com.intermanage.backend.agent.entity;

/** Makro, które można zlecić na komputerze sklepu (nazwy trafiają do bazy). Tylko makra składane przez serwer. */
public enum AgentMacroKind {
    /** Dostawa z bazy: kody ITM8 z błędem „ITM nie istnieje” z Obsługi BL bazy (wynik = lista kodów). */
    ITM8_COLLECT,
    /** Dostawa z bazy: nowe kody ITM8 na kartach artykułów. */
    ITM8_APPLY
}
