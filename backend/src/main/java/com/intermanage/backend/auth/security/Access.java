package com.intermanage.backend.auth.security;

/**
 * Wspólne wyrażenia do {@code @PreAuthorize}, żeby każdy moduł sprawdzał słowniki i kartę artykułu tak samo.
 *
 * <p>Zasady uprawnień (patrz CLAUDE.md, „Uwierzytelnianie i uprawnienia”):
 * <ul>
 * <li>VIEW to praca narzędziem: przeglądanie, wyszukiwanie, podpowiedzi, wydruki i pliki. Odczyt nigdy nie wymaga
 * EDIT, poza danymi kont i konfiguracją połączeń.</li>
 * <li>EDIT zmienia wspólne dane i ustawienia narzędzia.</li>
 * <li>Narzędzie wymaga tylko swojej kategorii. Dane z innych modułów, których potrzebuje w pracy (np. faktury KSeF przy
 * wprowadzaniu danych), dostaje przez swoje uprawnienie.</li>
 * <li>Słowniki ({@link #STORE}) widzi każdy z dostępem do sklepu: kartoteka, dostawcy, nomenklatura, sklepy
 * zaprzyjaźnione, zaokrąglanie cen.</li>
 * </ul>
 */
public final class Access {

    /** Słownik sklepu: każdy z dostępem do sklepu, bez względu na rolę. */
    public static final String STORE = "@storeAccess.canAccess(authentication, #storeId)";

    /** Kto widzi kartę artykułu (kartoteka, wykres sprzedaży artykułu, dostawy, protokoły, historia cen). */
    public static final String ARTICLE_STATS_AUTHORITIES =
            "hasAnyAuthority('SALES:VIEW', 'INVENTORY:VIEW', 'DATA_ENTRY:VIEW')";

    /** Karta artykułu w sklepie. */
    public static final String ARTICLE_STATS = ARTICLE_STATS_AUTHORITIES + " and " + STORE;

    private Access() {
    }
}
