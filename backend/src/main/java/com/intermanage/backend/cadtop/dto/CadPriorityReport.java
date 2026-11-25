package com.intermanage.backend.cadtop.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.cadtop.entity.CadPriorityRun;

/**
 * Artykuły, w których ITM8 z obowiązującego cadencier (plik TOP) nie jest priorytetowy, choć EAN jest w kartotece
 * sklepu. {@code nrCad} puste = żaden plik TOP dziś nie obowiązuje, {@code catalogGeneratedAt} puste = brak raportu
 * kartoteki. {@code setCount}: artykuły TOP z priorytetowym, aktywnym ITM8; {@code variantCount}: karta ma inny ITM8 z tego samego
 * cadencier (zwykły zamiast paletowego), więc nic nie trzeba; {@code absentCount}: artykułu TOP sklep nie ma.
 * {@code automatic}: po aktualizacji danych poprawia je komputer sklepu.
 */
public record CadPriorityReport(String nrCad, LocalDate from, LocalDateTime catalogGeneratedAt, int topCount,
        int setCount, int variantCount, int absentCount, boolean automatic, List<Item> items) {

    /** Co jest do zrobienia na karcie. */
    public enum Task {
        PRIORITY, ACTIVE, PRIORITY_AND_ACTIVE
    }

    public enum Match {
        MAIN_EAN, MULTI_CODE, SCALE_CODE
    }

    /** {@code FAILED}: zlecono, ale makro pominęło artykuł albo nowszy raport kartoteki dalej go pokazuje. */
    public enum Status {
        TODO, WAITING, FAILED
    }

    /**
     * Karta sklepu ({@code itm8}) i ITM8 z cadencier, który ma być na niej priorytetowy. {@code topKnown}: ten ITM8 był
     * kiedyś w kartotece. {@code alternatives}: inne ITM8 z cadencier z tym samym EAN-em (paletowy, inny rozmiar).
     */
    public record Item(String topItm8, String topEan, String topName, String topBrand, String topMark,
            BigDecimal topPurchasePrice, BigDecimal topSalePrice, String itm8, String ean, String name, String brand,
            String subfamilyCode, String supplier, BigDecimal stock, BigDecimal salePrice, BigDecimal purchasePrice,
            boolean active, Task task, Match match, boolean topKnown, List<Alternative> alternatives, Status status, Run run) {
    }

    public record Alternative(String itm8, String name, BigDecimal purchasePrice) {
    }

    /** Ostatnie zlecenie; {@code failure}: dlaczego makro pominęło artykuł. */
    public record Run(UUID id, CadPriorityRun.Source source, Instant createdAt, String createdBy, String failure) {
    }

    /** Zlecenie: makro do schowka (puste przy ręcznym oznaczeniu) i zapisane wpisy (do „Cofnij”). */
    public record RunResult(String macro, List<UUID> runIds, CadPriorityReport report) {
    }
}
