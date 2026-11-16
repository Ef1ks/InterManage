package com.intermanage.backend.mea.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
        List<Line> lines,
        List<AthosSend> athosSends,
        List<Order> orders,
        Queued queued) {

    /** Plik tego MEA czekający w kolejce sklepu na „Wyślij wszystkie” (null, gdy żaden nie czeka). */
    public record Queued(UUID id, Instant queuedAt, int positions, int colis, String createdBy) {
    }

    /** Zamówienie zapisane do ATHOS z aplikacji (plik R… w folderze comrnis); od najnowszego. */
    public record Order(Instant savedAt, String fileName, int positions, int colis, String createdBy) {
    }

    /** Wysyłka zamówienia tego MEA z ATHOS (dziennik Histo.Log): kiedy, ile pozycji i kartonów; od najnowszej. */
    public record AthosSend(LocalDateTime sentAt, int positions, int colis) {
    }

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
