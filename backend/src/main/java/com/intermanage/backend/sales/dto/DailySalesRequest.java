package com.intermanage.backend.sales.dto;

import java.time.LocalDate;

/**
 * Parametry raportu sprzedaży dziennej. Wydruk: {@code level} REGION (domyślnie) albo FAMILY, {@code measure} co
 * pokazać w komórkach, {@code layout} dni w kolumnach albo w wierszach. {@code grouping}: rejony albo
 * kategorie; {@code level} FAMILY pokazuje wtedy pod kategorią jej rejony.
 */
public record DailySalesRequest(LocalDate from, LocalDate to, Level level, Measure measure, Layout layout,
        Grouping grouping) {

    /** Wiersze: rejony nomenklatury (domyślnie) albo własne kategorie sklepu z rejonów. */
    public enum Grouping {
        NOMENCLATURE, CATEGORY
    }

    public enum Level {
        REGION, FAMILY
    }

    public enum Measure {
        GROSS, NET, MARGIN, MARGIN_PERCENT, QUANTITY
    }

    public enum Layout {
        /** Wiersze to nomenklatura, kolumny to dni. */
        DAYS_AS_COLUMNS,
        /** Wiersze to dni, kolumny to nomenklatura (miesiąc mieści się na jednej kartce). */
        DAYS_AS_ROWS
    }
}
