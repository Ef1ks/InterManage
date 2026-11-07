package com.intermanage.backend.stocktake.dto;

/** Dlaczego artykuł trzeba przeliczyć. */
public enum StocktakeReason {
    /** Policzono 0, a artykuł sprzedawał się po inwenturze bez dostawy: towar był. */
    SOLD_WHEN_ZERO,
    /** Sprzedano po inwenturze więcej, niż policzono, bez dostawy. */
    SOLD_MORE_THAN_COUNTED,
    /** Podobny artykuł ma przeciwną różnicę: ta sama ilość i bardzo podobna nazwa. */
    SWAP_STRONG,
    /** Podobny artykuł ma przeciwną różnicę, ale tylko jedno z tych dwóch się zgadza. */
    SWAP_POSSIBLE,
    /** Policzona ilość to stan teoretyczny z zamienionymi cyframi (27 i 72). */
    DIGITS_SWAPPED,
    /** Policzona ilość jest 10, 100 albo 1000 razy inna niż stan teoretyczny. */
    WRONG_SCALE,
    /** Policzono 0 przy dodatnim stanie teoretycznym. */
    NOT_COUNTED,
    /** Duża kwota różnicy bez innego wyjaśnienia. */
    LARGE_DIFFERENCE
}
