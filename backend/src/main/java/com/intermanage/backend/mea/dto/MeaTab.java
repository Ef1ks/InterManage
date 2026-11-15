package com.intermanage.backend.mea.dto;

/** Zakładki jak w module MEA eMEA, liczone z dat promocji i tego, czy zamówienie złożono. */
public enum MeaTab {

    /** Do wysłania: promocja jeszcze się nie zaczęła, a zamówienia nie złożono. */
    TO_SEND,
    /** Wysłane - promocja jeszcze się nie rozpoczęła. */
    SENT_NOT_STARTED,
    /** Trwa promocja. */
    ONGOING,
    /** Archiwum: promocja się skończyła. */
    ARCHIVE,
    /** Inne: bez dat promocji. */
    OTHER
}
