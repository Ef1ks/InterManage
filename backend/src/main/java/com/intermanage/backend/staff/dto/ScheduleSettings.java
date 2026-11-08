package com.intermanage.backend.staff.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ustawienia działu w sklepie z adresu: kto układa grafik, najmniejsza i docelowa obsada stanowisk i przerwy (GG:MM,
 * grafik ich nie pokazuje) i tolerancja kart czasu pracy w minutach.
 */
public record ScheduleSettings(List<UUID> managerIds, List<Person> people, List<Minimum> minimums,
        List<Break> breaks, int toleranceMinutes, boolean payEnabled) {

    /** Konto pracownika firmy, które można wskazać jako układającego. */
    public record Person(UUID userId, String name, String positionName) {
    }

    public record Minimum(UUID positionId, String positionName, Integer minCount, Integer targetCount) {
    }

    public record Break(String start, String end, boolean paid) {
    }
}
