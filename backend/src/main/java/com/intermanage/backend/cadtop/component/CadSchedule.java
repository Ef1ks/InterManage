package com.intermanage.backend.cadtop.component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Kolejność wejść cadencier z planów kolejnych lat. Plan późniejszego roku ma pierwszeństwo od swojego pierwszego dnia
 * (2027 zaczyna się 26.10.2026 i poprawia koniec planu 2026). Dni „zmiany ręczne” bez numeru pomijamy, a koniec numeru
 * to dzień przed początkiem kolejnego, bo daty końca w plikach są niejednolite. Numer zaplanowany dwa razy liczy się
 * od pierwszego wejścia.
 */
public final class CadSchedule {

    public record Plan(int year, List<PlanEntry> entries) {
    }

    /** {@code nrCad} pusty = „zmiany ręczne”. */
    public record PlanEntry(String nrCad, LocalDate startsOn, String comment) {
    }

    /** {@code endsOn} puste = ostatni numer w planach. */
    public record Entry(String nrCad, LocalDate startsOn, LocalDate endsOn, String comment, int planYear) {
    }

    private final List<Entry> entries;

    private CadSchedule(List<Entry> entries) {
        this.entries = entries;
    }

    public static CadSchedule of(List<Plan> plans) {
        List<Entry> starts = new ArrayList<>();
        LocalDate newerPlanFrom = null;
        for (Plan plan : plans.stream().sorted(Comparator.comparingInt(Plan::year).reversed()).toList()) {
            LocalDate limit = newerPlanFrom;
            LocalDate first = null;
            for (PlanEntry entry : plan.entries()) {
                if (entry.nrCad() == null || (limit != null && !entry.startsOn().isBefore(limit))) {
                    continue;
                }
                starts.add(new Entry(entry.nrCad(), entry.startsOn(), null, entry.comment(), plan.year()));
                first = first == null || entry.startsOn().isBefore(first) ? entry.startsOn() : first;
            }
            newerPlanFrom = first == null ? limit : first;
        }
        starts.sort(Comparator.comparing(Entry::startsOn));
        Set<String> seen = new HashSet<>();
        starts.removeIf(entry -> !seen.add(entry.nrCad()));
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            Entry entry = starts.get(i);
            LocalDate end = i + 1 < starts.size() ? starts.get(i + 1).startsOn().minusDays(1) : null;
            result.add(new Entry(entry.nrCad(), entry.startsOn(), end, entry.comment(), entry.planYear()));
        }
        return new CadSchedule(List.copyOf(result));
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Numer obowiązujący tego dnia: najpóźniejszy, który już wszedł. */
    public Optional<Entry> currentOn(LocalDate day) {
        return entries.stream().filter(entry -> !entry.startsOn().isAfter(day)).reduce((first, second) -> second);
    }

    /** Najbliższy numer po tym dniu. */
    public Optional<Entry> nextAfter(LocalDate day) {
        return entries.stream().filter(entry -> entry.startsOn().isAfter(day)).findFirst();
    }

    public Optional<Entry> find(String nrCad) {
        return entries.stream().filter(entry -> entry.nrCad().equals(nrCad)).findFirst();
    }
}
