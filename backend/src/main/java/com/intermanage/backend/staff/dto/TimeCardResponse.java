package com.intermanage.backend.staff.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Karty czasu pracy działu w sklepie za okres (dzień, tydzień, miesiąc): pracownicy z umową o pracę, dzień po dniu
 * grafik, odbicia i odcinki z ich stanem. Minuty wszędzie w całych minutach. Czas lokalny sklepu (Europe/Warsaw).
 */
public record TimeCardResponse(UUID departmentId, LocalDate from, LocalDate to, boolean canDecide,
        boolean canConfigure, boolean canSimulate, int toleranceMinutes, List<Day> days, List<Row> rows,
        List<WithoutCard> withoutCard) {

    /** Osoba z działu bez karty czasu pracy w okresie (zlecenie, B2B, bez umowy o pracę) z powodem. */
    public record WithoutCard(String name, String positionName, String reason) {
    }

    public record Day(LocalDate date, String holiday) {
    }

    /**
     * {@code rcpNumber}: numer karty RCP do Symfonii (nadany w aplikacji), inny niż numer karty NFC. {@code civil}:
     * ewidencja godzin zlecenia (same odbicia, bez decyzji), a nie karta umowy o pracę; osoba z obiema umowami ma dwa
     * wiersze.
     */
    public record Row(UUID employeeId, String name, String positionName, String cardUid, Integer rcpNumber,
            boolean civil, List<DayCard> days, Totals totals) {
    }

    /**
     * {@code plannedStart}/{@code plannedEnd}: zmiana z grafiku (koniec następnego dnia przy zmianie przez północ).
     * {@code absence}: nieobecność z Kadr (jak w grafiku: inne niż urlop wypoczynkowy i na żądanie tylko z dostępem do
     * Kadr). {@code openSince}: wejście bez wyjścia (dziś: w pracy; wcześniej: brak wyjścia, {@code missingExit}).
     */
    public record DayCard(LocalDate date, LocalDateTime plannedStart, LocalDateTime plannedEnd, String absence,
            List<Punch> punches, LocalDateTime openSince, boolean missingExit, List<Segment> segments, int planned,
            int worked, int overtime, int pending, int shortfall, LocalDateTime civilStart, LocalDateTime civilEnd) {
    }

    public record Punch(UUID id, LocalDateTime at, String source) {
    }

    /**
     * {@code part}: WORK, BREAK, OVERTIME, SHORTFALL; {@code status}: {@code TimeCard.Status}. {@code signedFile}:
     * podpisane polecenie albo wniosek dołączony do decyzji o nadgodzinach.
     */
    public record Segment(String part, LocalDateTime start, LocalDateTime end, int minutes, String status,
            UUID decisionId, String decidedBy, String note, SignedFile signedFile) {
    }

    public record SignedFile(UUID id, String fileName) {
    }

    public record Totals(int planned, int worked, int overtime, int pending, int shortfall) {
    }
}
