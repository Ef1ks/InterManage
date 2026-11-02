package com.intermanage.backend.sales.dto;

/** Po czym raport protokołów grupuje artykuły. */
public enum ProtocolReportGrouping {

    /** Każdy artykuł (ITM8) osobno. */
    ARTICLE,
    /** Marka z linii protokołu. */
    BRAND,
    /** Ostatni dostawca artykułu z przyjęć PZ, bez przyjęcia dostawca z linii protokołu. */
    SUPPLIER,
    /** Rejon, rodzina albo podrodzina nomenklatury linii protokołu. */
    NOMENCLATURE
}
