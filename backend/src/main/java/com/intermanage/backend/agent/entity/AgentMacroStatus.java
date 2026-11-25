package com.intermanage.backend.agent.entity;

/** Stan makra zleconego na komputerze sklepu (nazwy trafiają do bazy). */
public enum AgentMacroStatus {
    /**
     * Czeka w kolejce sklepu: trwa inne makro albo żaden komputer nie jest teraz wolny. Rusza samo, po kolei według
     * chwili zlecenia, i nie wygasa.
     */
    QUEUED,
    /** Zaproponowane komputerowi: agent odlicza, a pracownik przy nim może je odrzucić. */
    OFFERED,
    /** Odtwarzacz wykonuje makro. */
    RUNNING,
    DONE,
    FAILED,
    /** Makro serwera (pliki, zmiany cen, ITM8 po aktualizacji): żaden komputer nie był wolny, serwer ponowi sam. */
    NO_COMPUTER,
    /** Zlecający anulował przed startem. */
    CANCELLED;

    /** Na komputerze: odliczanie albo wykonanie (w sklepie najwyżej jedno). */
    public boolean active() {
        return this == OFFERED || this == RUNNING;
    }

    /** Skończone (wynik, błąd, anulowanie, brak komputera). */
    public boolean ended() {
        return this != QUEUED && !active();
    }
}
