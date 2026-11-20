package com.intermanage.backend.task.entity;

/** Stan zadania; nazwa trafia do bazy. */
public enum TaskStatus {
    /** Do zrobienia (także odesłane do poprawy). */
    OPEN,
    /** Wykonawca oznaczył jako wykonane, czeka na przyjęcie przez zlecającego. */
    DONE,
    /** Zlecający przyjął wykonanie. */
    ACCEPTED
}
