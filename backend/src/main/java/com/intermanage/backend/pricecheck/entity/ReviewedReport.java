package com.intermanage.backend.pricecheck.entity;

/** Raport, którego dotyczy status wyjaśniania przyjęcia albo lista pomijanych dostawców. Nazwa stałej trafia do bazy. */
public enum ReviewedReport {
    /** Zakup bazowy poza bazą. */
    BASE_PURCHASE,
    /** Przepłacone artykuły. */
    OVERPAYMENT,
    /** Za niskie marże. */
    LOW_MARGIN,
    /** Artykuły z za niską marżą (tylko własna lista pomijanych dostawców, bez wyjaśniania). */
    ARTICLE_MARGIN
}
