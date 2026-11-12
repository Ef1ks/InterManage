package com.intermanage.backend.staff.component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.EmploymentType;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.WorkPeriodKind;

/**
 * Co kadry powinny sprawdzić w kartotece. Reguły tylko ostrzegają, niczego nie blokują: program nie zna wszystkich
 * okoliczności (zapisów umowy, regulaminu, zgód), a decyzja należy do pracodawcy.
 */
public final class StaffCompliance {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int SOON_DAYS = 30;
    private static final int FIXED_TERM_MONTHS = 33;
    private static final int FIXED_TERM_CONTRACTS = 3;
    /** Pora nocna to 8 godzin między 21 a 7 (art. 151(7) KP); dokładne godziny są w regulaminie, tu najczęstsze 22-6. */
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);

    private StaffCompliance() {
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public record Warning(Severity severity, String message, String basis) {
    }

    public static List<Warning> check(StaffFile file, LocalDate today) {
        List<Warning> result = new ArrayList<>();
        StaffContract current = file.currentContract(today);
        if (current == null) {
            result.add(new Warning(Severity.ERROR, "Brak obowiązującej umowy", null));
        } else {
            contractEnd(current, today, result);
        }
        fixedTerm(file, today, result);
        trial(file, result);
        // Stałych godzin przy zleceniu i B2B celowo nie ostrzegamy: sklep o tym wie i tak pracuje.
        boolean employee = current != null && current.isEmployment();
        if (employee) {
            deadlines(file.profile(), today, result);
            hours(file, today, result);
            leave(file, today, result);
        }
        disability(file, today, result);
        newPeriods(file, today, result);
        youth(file.profile(), today, result);
        return result.stream().sorted(Comparator.comparing(Warning::severity)).toList();
    }

    /** Uprawnienia z ustawy o rehabilitacji wynikające z orzeczenia obowiązującego danego dnia. */
    public static List<String> rights(StaffFile file, LocalDate today) {
        StaffDisability disability = file.disabilityOn(today);
        if (disability == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (disability.isDoctorConsent()) {
            result.add("Zgoda lekarza (art. 16): normy czasu pracy i zakazy z art. 15 nie obowiązują");
        } else {
            result.add(disability.isSignificant() ? "Do 7 godzin na dobę i 35 tygodniowo (art. 15 ust. 2)"
                    : "Do 8 godzin na dobę i 40 tygodniowo (art. 15 ust. 1)");
            result.add("Bez pracy w nocy i w nadgodzinach (art. 15 ust. 3)");
        }
        result.add("15 minut dodatkowej przerwy wliczanej do czasu pracy (art. 17)");
        if (disability.isSignificant()) {
            result.add("10 dni urlopu dodatkowego po przepracowaniu roku od zaliczenia do stopnia (art. 19)");
            result.add("Do 21 dni zwolnienia na turnus rehabilitacyjny, razem z urlopem dodatkowym (art. 20)");
        }
        return result;
    }

    private static void contractEnd(StaffContract contract, LocalDate today, List<Warning> result) {
        if (contract.getEndDate() != null && ChronoUnit.DAYS.between(today, contract.getEndDate()) <= SOON_DAYS) {
            result.add(new Warning(Severity.WARNING, "Umowa kończy się " + DATE.format(contract.getEndDate()), null));
        }
    }

    private static void fixedTerm(StaffFile file, LocalDate today, List<Warning> result) {
        List<StaffContract> fixed = file.employment().stream()
                .filter(contract -> contract.getEmploymentType() == EmploymentType.FIXED)
                .filter(contract -> !contract.isFixedTermExempt())
                .toList();
        if (fixed.isEmpty()) {
            return;
        }
        if (fixed.size() > FIXED_TERM_CONTRACTS) {
            result.add(new Warning(Severity.ERROR, "To już " + fixed.size() + ". umowa na czas określony: od "
                    + DATE.format(fixed.get(FIXED_TERM_CONTRACTS).getStartDate())
                    + " pracownik jest zatrudniony na czas nieokreślony", "art. 25¹ § 3 KP"));
        }
        long months = 0;
        for (StaffContract contract : fixed) {
            LocalDate end = contract.getEndDate() == null ? today : contract.getEndDate();
            months += Period.between(contract.getStartDate(), end.plusDays(1)).toTotalMonths();
        }
        if (months > FIXED_TERM_MONTHS) {
            result.add(new Warning(Severity.ERROR, "Umowy na czas określony trwają łącznie " + months
                    + " mies., ponad limit 33: po jego upływie pracownik jest zatrudniony na czas nieokreślony",
                    "art. 25¹ § 1 i § 3 KP"));
        } else if (months >= FIXED_TERM_MONTHS - 3) {
            result.add(new Warning(Severity.WARNING, "Umowy na czas określony trwają łącznie " + months
                    + " mies. z limitu 33", "art. 25¹ § 1 KP"));
        }
    }

    private static void trial(StaffFile file, List<Warning> result) {
        List<StaffContract> trials = file.employment().stream()
                .filter(contract -> contract.getEmploymentType() == EmploymentType.TRIAL)
                .toList();
        for (StaffContract contract : trials) {
            if (contract.getEndDate() != null
                    && Period.between(contract.getStartDate(), contract.getEndDate().plusDays(1)).toTotalMonths() >= 3
                    && contract.getStartDate().plusMonths(3).isBefore(contract.getEndDate().plusDays(1))) {
                result.add(new Warning(Severity.WARNING, "Umowa na okres próbny trwa ponad 3 miesiące", "art. 25 § 2 KP"));
            }
        }
        if (trials.size() > 1) {
            result.add(new Warning(Severity.WARNING,
                    "Kolejna umowa na okres próbny jest dopuszczalna tylko przy innym rodzaju pracy", "art. 25 § 3 KP"));
        }
    }

    private static void deadlines(StaffProfile profile, LocalDate today, List<Warning> result) {
        deadline("Badania lekarskie", profile == null ? null : profile.getMedicalExamUntil(), today, "art. 229 § 4 KP",
                true, result);
        deadline("Szkolenie BHP", profile == null ? null : profile.getBhpTrainingUntil(), today, "art. 237³ KP", true,
                result);
        deadline("Badania sanitarno-epidemiologiczne", profile == null ? null : profile.getSanitaryUntil(), today, null,
                false, result);
    }

    private static void deadline(String label, LocalDate until, LocalDate today, String basis, boolean required,
            List<Warning> result) {
        if (until == null) {
            if (required) {
                result.add(new Warning(Severity.WARNING, label + ": brak daty ważności", basis));
            }
        } else if (until.isBefore(today)) {
            result.add(new Warning(Severity.ERROR, label + ": termin minął " + DATE.format(until)
                    + ", nie wolno dopuścić do pracy", basis));
        } else if (ChronoUnit.DAYS.between(today, until) <= SOON_DAYS) {
            result.add(new Warning(Severity.WARNING, label + ": ważność do " + DATE.format(until), basis));
        }
    }

    private static void hours(StaffFile file, LocalDate today, List<Warning> result) {
        StaffDisability disability = file.disabilityOn(today);
        boolean protectedEmployee = disability != null && !disability.isDoctorConsent();
        int limit = protectedEmployee && disability.isSignificant() ? 7 : 8;
        String basis = protectedEmployee ? "art. 15 ustawy o rehabilitacji" : "art. 129 § 1 KP";
        boolean tooLong = file.shifts().stream().anyMatch(shift -> minutes(shift) > limit * 60L);
        if (tooLong) {
            result.add(new Warning(protectedEmployee ? Severity.ERROR : Severity.WARNING,
                    "Stałe godziny przekraczają " + limit + " godzin na dobę", basis));
        }
        if (protectedEmployee && file.shifts().stream().anyMatch(StaffCompliance::atNight)) {
            result.add(new Warning(Severity.ERROR, "Stałe godziny wypadają w porze nocnej (przyjęto 22-6, sprawdź "
                    + "regulamin): osoby z niepełnosprawnością nie wolno zatrudniać w nocy",
                    "art. 15 ust. 3 ustawy o rehabilitacji"));
        }
    }

    private static long minutes(StaffShift shift) {
        long minutes = Duration.between(shift.getStart(), shift.getEnd()).toMinutes();
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    private static boolean atNight(StaffShift shift) {
        return shift.getEnd().isBefore(shift.getStart()) || shift.getStart().isBefore(NIGHT_END)
                || shift.getEnd().isAfter(NIGHT_START);
    }

    private static void leave(StaffFile file, LocalDate today, List<Warning> result) {
        int year = today.getYear();
        LeaveCalculator.LeaveYear leave = LeaveCalculator.forYear(file, year, today);
        if (leave.remainingDays() < 0) {
            result.add(new Warning(Severity.ERROR, "Wykorzystano o " + -leave.remainingDays()
                    + " dni urlopu więcej, niż przysługuje", "art. 154 KP"));
        }
        if (LeaveCalculator.missesOpening(file, year)) {
            result.add(new Warning(Severity.WARNING, "Uzupełnij urlop z poprzednich lat: bez tego program liczy tylko "
                    + "bieżący wymiar", null));
        }
        int overdue = LeaveCalculator.overdueDays(file, year, today);
        if (overdue > 0) {
            result.add(new Warning(Severity.ERROR, "Urlop zaległy z poprzednich lat po terminie: " + overdue
                    + " dni, należało udzielić do 30 września", "art. 168 KP"));
        }
        for (Map.Entry<LeaveKind, Integer> used : leave.usage().entrySet()) {
            Integer limit = used.getKey().limitDays();
            if (limit != null && used.getValue() > limit) {
                result.add(new Warning(Severity.WARNING, label(used.getKey()) + ": wykorzystano " + used.getValue()
                        + " dni z limitu " + limit, basis(used.getKey())));
            }
        }
        int rehabilitation = leave.usage().getOrDefault(LeaveKind.REHABILITATION, 0);
        if (rehabilitation > 0 && rehabilitation + leave.additionalDays() > 21) {
            result.add(new Warning(Severity.WARNING,
                    "Urlop dodatkowy razem z turnusem nie może przekroczyć 21 dni w roku",
                    "art. 20 ust. 3 ustawy o rehabilitacji"));
        }
    }

    public static String label(LeaveKind kind) {
        return switch (kind) {
            case ON_DEMAND -> "Urlop na żądanie";
            case CHILD_CARE -> "Opieka nad dzieckiem";
            case FORCE_MAJEURE -> "Siła wyższa";
            case CARE -> "Urlop opiekuńczy";
            case REHABILITATION -> "Turnus rehabilitacyjny";
            case B2B_PAID -> "Płatna przerwa (B2B)";
            default -> "Urlop";
        };
    }

    public static String basis(LeaveKind kind) {
        return switch (kind) {
            case ON_DEMAND -> "art. 167² KP";
            case CHILD_CARE -> "art. 188 KP";
            case FORCE_MAJEURE -> "art. 148¹ KP";
            case CARE -> "art. 173¹ KP";
            case REHABILITATION -> "art. 20 ustawy o rehabilitacji";
            default -> null;
        };
    }

    private static void disability(StaffFile file, LocalDate today, List<Warning> result) {
        StaffDisability current = file.disabilityOn(today);
        if (current != null && current.getEndDate() != null
                && ChronoUnit.DAYS.between(today, current.getEndDate()) <= SOON_DAYS) {
            result.add(new Warning(Severity.WARNING, "Orzeczenie o niepełnosprawności ważne do "
                    + DATE.format(current.getEndDate()), null));
        }
    }

    private static void newPeriods(StaffFile file, LocalDate today, List<Warning> result) {
        boolean waiting = file.workPeriods().stream()
                .anyMatch(period -> period.getKind() != WorkPeriodKind.EMPLOYMENT && !period.isDocumented());
        boolean employedOnStart = file.contracts().stream()
                .anyMatch(contract -> contract.getKind() == ContractKind.EMPLOYMENT
                        && contract.activeOn(Seniority.NEW_PERIODS_FROM));
        if (waiting) {
            result.add(new Warning(Severity.WARNING, employedOnStart
                    ? "Okresy czekają na zaświadczenie z ZUS: pracownik ma czas do "
                            + DATE.format(Seniority.NEW_PERIODS_DOCUMENT_UNTIL) + ", później nie wliczają się do stażu"
                    : "Okresy czekają na zaświadczenie z ZUS i do tego czasu nie wliczają się do stażu",
                    "art. 302¹ KP, art. 5 ustawy z 26.09.2025"));
        }
    }

    private static void youth(StaffProfile profile, LocalDate today, List<Warning> result) {
        if (profile != null && profile.getBirthDate() != null
                && Period.between(profile.getBirthDate(), today).getYears() < 18) {
            result.add(new Warning(Severity.WARNING,
                    "Osoba niepełnoletnia: obowiązują szczególne zasady zatrudniania młodocianych", "dział IX KP"));
        }
    }
}
