package com.intermanage.backend.bakery.component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numery i daty WZ z pól WZ faktury. Każdy dostawca pisze je inaczej: Sikora "DD nr 14803/10/2026/DD (2026-10-21)",
 * Sofian samo "1497", ale za to fakturuje okres (OkresFa) z jedną WZ dziennie. Gdy WZ bez dat jest dokładnie tyle, ile
 * dni okresu, dostają kolejne dni po numerach jako data szacowana; inaczej data zostaje pusta, do wpisania.
 */
public final class DeliveryNotes {

    private static final Pattern NOTE = Pattern.compile(
            "^(?:(?:DD|WZ)\\s+)?(?:nr\\.?\\s+)?(.+?)\\s*(?:\\((\\d{4}-\\d{2}-\\d{2})\\))?$",
            Pattern.CASE_INSENSITIVE);

    public record Note(String number, LocalDate date, boolean estimated) {
    }

    private DeliveryNotes() {
    }

    public static List<Note> parse(List<String> raw, LocalDate periodFrom, LocalDate periodTo) {
        List<Note> notes = new ArrayList<>();
        for (String text : raw) {
            Matcher matcher = NOTE.matcher(text.strip());
            if (matcher.matches() && notes.stream().noneMatch(note -> note.number().equals(matcher.group(1)))) {
                notes.add(new Note(matcher.group(1), matcher.group(2) != null ? LocalDate.parse(matcher.group(2)) : null,
                        false));
            }
        }
        // Numery rosną z czasem: 999 jest przed 1000, więc najpierw po długości.
        notes.sort(Comparator.comparing(Note::date, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(note -> note.number().length()).thenComparing(Note::number));
        boolean withoutDates = !notes.isEmpty() && notes.stream().allMatch(note -> note.date() == null);
        if (!withoutDates || periodFrom == null || periodTo == null
                || ChronoUnit.DAYS.between(periodFrom, periodTo) + 1 != notes.size()) {
            return notes;
        }
        List<Note> dated = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            dated.add(new Note(notes.get(i).number(), periodFrom.plusDays(i), true));
        }
        return dated;
    }
}
