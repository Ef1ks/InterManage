package com.intermanage.backend.promotion.dto;

/** Ocena promocji w raporcie skuteczności promocji (liczona przy każdym raporcie, nie zapisywana). */
public enum PromotionVerdict {
    /** Marża zł w promocji co najmniej taka, jak bez promocji. */
    PROFITABLE,
    /** Nieopłacalna, a sprzedaż prawie nie wzrosła: klienci nie zareagowali. */
    NO_REACTION,
    /** Nieopłacalna, sprzedaż wzrosła, ale za mało na tak dużą obniżkę. */
    TOO_DEEP,
    /** Sprzedaż w promocji poniżej kosztu zakupu (FIFO): większa sprzedaż tylko pogłębia stratę. */
    BELOW_COST,
    /** Bez bazy do porównania: artykuł nie sprzedawał się przed promocją albo prawie wszystkie dni były promocyjne. */
    NO_BASELINE
}
