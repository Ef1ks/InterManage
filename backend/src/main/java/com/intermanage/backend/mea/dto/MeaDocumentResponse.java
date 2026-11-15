package com.intermanage.backend.mea.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.intermanage.backend.dataentry.dto.CatalogProduct;

/**
 * Dokument MEA z formularza portalu: nagłówek (tytuł, okres promocji i rezerwacji, daty rozrzutów R1-R4) i pozycje.
 * Ceny „MEA” to warunki promocji, „parallel” to ITM8 równoległy (artykuł stały z cadencier, CAD w eMEA). Przy pozycji
 * {@code product} to artykuł z kartoteki sklepu (stan, rotacja, cena na półce), a {@code null}, gdy go nie ma.
 */
public record MeaDocumentResponse(
        String number,
        String title,
        LocalDate promoFrom,
        LocalDate promoTo,
        LocalDate reservationFrom,
        LocalDate reservationTo,
        List<LocalDate> dispatches,
        List<Line> lines) {

    public record Line(
            int lp,
            String meaItm8,
            String parallelItm8,
            String ean,
            String name,
            String brand,
            Integer packsPerPallet,
            Integer unitsPerPack,
            Integer vatPercent,
            BigDecimal purchasePrice,
            BigDecimal parallelPurchasePrice,
            BigDecimal shelfPrice,
            BigDecimal parallelShelfPrice,
            BigDecimal margin,
            BigDecimal parallelMargin,
            Integer guaranteedDays,
            String note,
            CatalogProduct product) {
    }
}
