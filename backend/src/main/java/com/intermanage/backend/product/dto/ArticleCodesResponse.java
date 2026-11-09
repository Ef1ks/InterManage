package com.intermanage.backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Kody przypisane do artykułu w sklepie: główny EAN z kartoteki ({@code mainEan}, pusty, gdy artykułu nie ma
 * w kartotece), każdy EAN z multikodów (obowiązujący i dawny) i z par ITM8 + EAN z raportów kartoteki. Przy każdym
 * EAN-ie inne artykuły (ITM8), do których ten sam kod jest przypisany teraz.
 */
public record ArticleCodesResponse(String itm8, String name, String mainEan, List<Code> eans) {

    /**
     * Jeden EAN artykułu. {@code main}: bieżący EAN z kartoteki. {@code catalogFirstSeen}/{@code catalogLastSeen}:
     * pierwszy i ostatni raport kartoteki z tą parą (puste, gdy pary nie ma w raportach). {@code multicode}: wpis
     * z multikodów, pusty, gdy kodu tam nie ma.
     */
    public record Code(
            String ean,
            boolean main,
            LocalDateTime catalogFirstSeen,
            LocalDateTime catalogLastSeen,
            Multicode multicode,
            List<OtherArticle> otherArticles) {
    }

    /**
     * Wpis z multikodów. {@code current}: kod jest w najnowszym wgranym raporcie multikodów. {@code priceFactor}: cena
     * kodu = współczynnik × cena artykułu (kody z etykiet obniżek).
     */
    public record Multicode(
            boolean prio,
            boolean current,
            String saleUnit,
            BigDecimal salePack,
            BigDecimal priceFactor,
            BigDecimal salePrice,
            LocalDate removalDate,
            LocalDateTime firstSeen,
            LocalDateTime lastSeen) {
    }

    /** Inny artykuł z tym samym kodem. {@code source}: MAIN (jego główny EAN), MULTICODE, CATALOG (para z raportu). */
    public record OtherArticle(String itm8, String name, String source) {
    }
}
