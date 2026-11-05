package com.intermanage.backend.pricecheck.entity;

/** Raport, którego dotyczy status wyjaśniania przyjęcia. Nazwa stałej trafia do bazy. */
public enum ReviewedReport {
    /** Zakup bazowy poza bazą. */
    BASE_PURCHASE,
    /** Przepłacone artykuły. */
    OVERPAYMENT,
    /** Za niskie marże. */
    LOW_MARGIN
}
