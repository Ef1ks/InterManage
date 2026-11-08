package com.intermanage.backend.staff.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.entity.ScheduleDayOffKind;
import com.intermanage.backend.staff.entity.TimeOffInitiative;
import com.intermanage.backend.store.dto.StoreSummary;

/**
 * Grafik działu na miesiąc. Układający widzi stan bieżący z wymiarem i ostrzeżeniami, a pozostali ostatnią publikację,
 * bez liczb z umów. {@code status}: DRAFT nigdy nie opublikowany, PUBLISHED zgodny z ostatnią wersją, CHANGED zmieniony po
 * publikacji. {@code viewedVersion}: oglądana stara wersja, tylko do odczytu.
 */
public record ScheduleResponse(UUID departmentId, String month, String status, boolean canEdit, boolean canConfigure,
        Integer viewedVersion, LocalDate periodStart, LocalDate periodEnd, List<Version> versions, List<Day> days,
        List<Position> positions, List<Group> groups, List<Row> rows, List<StoreSummary> stores, boolean showsPay) {

    /** Własna grupa pracowników. Bez grup tabela dzieli ludzi według stanowisk. */
    public record Group(UUID id, String name) {
    }

    /** {@code late}: przekazana później niż tydzień przed miesiącem (art. 129 § 3 KP). */
    public record Version(int number, Instant publishedAt, String publishedBy, boolean late) {
    }

    /** {@code closed}: święto, w które sklep nie jest czynny. Niedziele sklep ma czynne. */
    public record Day(LocalDate date, String holiday, boolean closed) {
    }

    /** {@code counts}: obsada kolejnych dni z {@link ScheduleResponse#days}. */
    public record Position(UUID id, String name, Integer minCount, Integer targetCount, List<ShiftResponse> shifts, List<Integer> counts) {
    }

    /**
     * {@code guest}: pracownik spoza działu albo sklepu, który ma tu dni. Liczby w minutach; bez umowy o pracę puste.
     * {@code owedDays}: przepracowane niedziele i święta bez WN, tylko dla układającego.
     * {@code outOfContractDays}: dni miesiąca bez żadnej umowy; pusta, gdy Kadry nie mają umów tej osoby.
     */
    public record Row(UUID employeeId, String name, UUID positionId, String positionName, UUID groupId, String homeStore,
            boolean guest, String contract, Integer monthMinutes, Integer monthNormMinutes, Integer periodMinutes,
            Integer periodNormMinutes, List<ShiftResponse> fixedShifts, List<Cell> cells, List<Warning> warnings,
            List<LocalDate> owedDays, List<LocalDate> outOfContractDays, Integer monthTargetMinutes,
            Integer carriedMinutes, Integer dailyOvertimeMinutes, LocalDate actualUntil, Integer timeOffMinutes,
            Integer toCompensateMinutes, boolean civil, BigDecimal hourlyRate, BigDecimal pay,
            BigDecimal overtimeSupplement) {
    }

    /**
     * Zmiana albo sama nieobecność z Kadr. {@code store}: numer innego sklepu, gdy ten dzień jest poza grafikiem.
     * {@code absence}: rodzaj nieobecności. Urlop wypoczynkowy i na żądanie widzi każdy, kto widzi grafik; resztę
     * (turnus rehabilitacyjny, opieka nad dzieckiem) tylko osoba z dostępem do Kadr, inni dostają {@code OTHER}.
     * {@code dayOff}: oznaczony dzień wolny, przy WN {@code forDate} to niedziela albo święto, za które przysługuje.
     * {@code leave}: wpis nieobecności z Kadr (do poprawienia albo usunięcia z grafiku), tylko dla osób z dostępem do
     * Kadr. {@code timeOffMinutes}: czas wolny za nadgodziny tego dnia (tylko dla układającego).
     */
    public record Cell(LocalDate date, String start, String end, UUID storeId, String store, boolean absent,
            String absence, ScheduleDayOffKind dayOff, LocalDate forDate, StaffRecordResponse.Leave leave,
            Integer timeOffMinutes, TimeOffInitiative timeOffInitiative) {
    }

    public record Warning(LocalDate date, String message, String basis) {
    }
}
