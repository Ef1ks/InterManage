package com.intermanage.backend.agent.entity;

/** Stan makra zleconego na komputerze sklepu (nazwy trafiają do bazy). */
public enum AgentMacroStatus {
    /** Zaproponowane komputerowi: agent odlicza, a pracownik przy nim może je odrzucić. */
    OFFERED,
    /** Odtwarzacz wykonuje makro. */
    RUNNING,
    DONE,
    FAILED,
    /** Żaden komputer nie był wolny albo każdy odrzucił. */
    NO_COMPUTER,
    /** Zlecający anulował przed startem. */
    CANCELLED;

    public boolean active() {
        return this == OFFERED || this == RUNNING;
    }
}
