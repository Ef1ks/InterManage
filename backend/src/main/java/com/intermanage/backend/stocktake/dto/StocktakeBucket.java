package com.intermanage.backend.stocktake.dto;

/** Co zrobić z różnicą: przeliczyć, sprawdzić dokumenty, rozliczyć produkcję albo zostawić. */
public enum StocktakeBucket {
    /** Różnica wygląda na błąd liczenia. */
    RECOUNT,
    /** Stan teoretyczny ujemny: liczenie jest zwykle dobre, a zły jest stan w systemie (np. brakujący dokument). */
    SYSTEM_STOCK,
    /** Rejony mięsa, drobiu i garmażu: surowiec bez rozchodu i wyrób bez przychodu, czyli nierozliczona produkcja. */
    PRODUCTION,
    /** Drobna różnica bez żadnego powodu do przeliczenia. */
    REST
}
