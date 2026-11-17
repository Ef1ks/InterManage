package com.intermanage.backend.staff.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.push.service.PushService;
import com.intermanage.backend.staff.dto.MyTimeAnomaliesResponse;
import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffTimeAnomalyNotice;
import com.intermanage.backend.staff.entity.StaffTimeRequest;
import com.intermanage.backend.staff.entity.TimeRequestKind;
import com.intermanage.backend.staff.entity.TimeRequestStatus;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffTimeAnomalyNoticeRepository;
import com.intermanage.backend.staff.repository.StaffTimeRequestRepository;

/**
 * Różnice między odbiciami a grafikiem z punktu widzenia pracownika: czas poza grafikiem bez decyzji, nieusprawiedliwiony
 * brak i wejście bez wyjścia. Pracownik widzi je w aplikacji i składa na nie wnioski; o nowych dostaje powiadomienie
 * push (każda różnica raz, {@link #notifyNew}).
 */
@Service
public class StaffTimeAnomalyService {

    /** Ile dni wstecz (z dziś) aplikacja pokazuje różnice do wyjaśnienia. */
    static final int DAYS = 14;
    /** Powiadomienie tylko o świeżych różnicach: po wdrożeniu nie przychodzi lawina o starych dniach. */
    private static final Duration NOTICE_WINDOW = Duration.ofHours(36);

    public static final String MISSING_EXIT = "MISSING_EXIT";

    private static final List<TimeRequestKind> OVERTIME_KINDS = List.of(TimeRequestKind.OVERTIME,
            TimeRequestKind.WORK_OFF);
    private static final List<TimeRequestKind> SHORTFALL_KINDS = List.of(TimeRequestKind.LATE_OR_EARLY,
            TimeRequestKind.PRIVATE_EXIT, TimeRequestKind.FORCE_MAJEURE, TimeRequestKind.CHILD_CARE,
            TimeRequestKind.OTHER_PAID);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d.MM", Locale.forLanguageTag("pl"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private final StaffTimeService time;
    private final StaffEmployeeRepository employees;
    private final StaffTimeRequestRepository requests;
    private final StaffTimeAnomalyNoticeRepository notices;
    private final PushService push;

    public StaffTimeAnomalyService(StaffTimeService time, StaffEmployeeRepository employees,
            StaffTimeRequestRepository requests, StaffTimeAnomalyNoticeRepository notices, PushService push) {
        this.time = time;
        this.employees = employees;
        this.requests = requests;
        this.notices = notices;
        this.push = push;
    }

    /** Różnica w karcie dnia. */
    record Anomaly(LocalDate date, String part, LocalDateTime start, LocalDateTime end, int minutes,
            LocalDateTime plannedStart, LocalDateTime plannedEnd) {
    }

    /**
     * Różnice z wiersza karty, które już się wydarzyły ({@code end} najpóźniej teraz): spóźnienie widać od razu, a
     * brak do końca zmiany dopiero po niej. Brak wyjścia ze starszych dni (a nie dzisiejsze „w pracy”).
     */
    static List<Anomaly> of(TimeCardResponse.Row row, LocalDateTime now) {
        List<Anomaly> result = new ArrayList<>();
        for (TimeCardResponse.DayCard day : row.days()) {
            if (day.missingExit() && day.openSince() != null) {
                result.add(new Anomaly(day.date(), MISSING_EXIT, day.openSince(), null, 0, day.plannedStart(),
                        day.plannedEnd()));
            }
            for (TimeCardResponse.Segment segment : day.segments()) {
                boolean waiting = segment.part().equals("OVERTIME") && segment.status().equals("PENDING")
                        || segment.part().equals("SHORTFALL") && segment.status().equals("UNEXCUSED");
                if (waiting && !segment.end().isAfter(now)) {
                    result.add(new Anomaly(day.date(), segment.part(), segment.start(), segment.end(),
                            segment.minutes(), day.plannedStart(), day.plannedEnd()));
                }
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public MyTimeAnomaliesResponse own(UUID userId) {
        StaffEmployee employee = employees.findActiveOfUsers(List.of(userId)).stream().findFirst().orElse(null);
        if (employee == null) {
            return new MyTimeAnomaliesResponse(false, 0, List.of());
        }
        LocalDateTime now = LocalDateTime.now(StoreTime.ZONE);
        LocalDate today = now.toLocalDate();
        TimeCardResponse.Row row = time.view(employee.getStore().getId(),
                employee.getPosition().getDepartment().getId(), today.minusDays(DAYS - 1), today, null).rows().stream()
                .filter(found -> !found.civil() && found.employeeId().equals(employee.getId())).findFirst()
                .orElse(null);
        if (row == null) {
            return new MyTimeAnomaliesResponse(true, 0, List.of());
        }
        // Ostatni wniosek na każdy odcinek (od najnowszego), żeby pokazać jego stan zamiast przycisku.
        Map<String, StaffTimeRequest> lastRequest = new HashMap<>();
        requests.findOfEmployees(List.of(employee.getId())).stream()
                .filter(request -> request.getStartAt() != null)
                .forEach(request -> lastRequest.putIfAbsent(key(request.getStartAt(), request.getEndAt()), request));
        List<MyTimeAnomaliesResponse.Item> items = of(row, now).stream()
                .sorted(Comparator.comparing(Anomaly::start).reversed())
                .map(anomaly -> {
                    StaffTimeRequest request = anomaly.end() == null ? null
                            : lastRequest.get(key(anomaly.start(), anomaly.end()));
                    return new MyTimeAnomaliesResponse.Item(anomaly.date(), anomaly.part(), anomaly.start(),
                            anomaly.end(), anomaly.minutes(), anomaly.plannedStart(), anomaly.plannedEnd(),
                            kinds(anomaly.part()), request == null ? null
                                    : new MyTimeAnomaliesResponse.Request(request.getId(), request.getKind(),
                                            request.getStatus()));
                })
                .toList();
        int open = (int) items.stream().filter(item -> !item.part().equals(MISSING_EXIT)
                && (item.request() == null || item.request().status() == TimeRequestStatus.CANCELLED)).count();
        return new MyTimeAnomaliesResponse(true, open, items);
    }

    /**
     * Powiadamia pracowników z aplikacją (z tokenem push) o nowych różnicach z ostatnich {@link #NOTICE_WINDOW}: każda
     * raz, a kilka naraz w jednym powiadomieniu. Karta liczy się działami (jedno zapytanie na dział w sklepie).
     */
    @Transactional
    public int notifyNew() {
        List<UUID> withTokens = push.usersWithTokens();
        if (withTokens.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(StoreTime.ZONE);
        LocalDate today = now.toLocalDate();
        List<StaffEmployee> people = employees.findActiveOfUsers(withTokens);
        record Place(UUID storeId, UUID departmentId) {
        }
        Map<Place, List<StaffEmployee>> byPlace = people.stream().collect(Collectors.groupingBy(
                employee -> new Place(employee.getStore().getId(), employee.getPosition().getDepartment().getId()),
                LinkedHashMap::new, Collectors.toList()));
        Set<String> known = new HashSet<>();
        // Różnica z wnioskiem (czeka albo rozstrzygnięty) jest już wyjaśniana: o niej nie przypominamy.
        requests.findOfEmployees(people.stream().map(StaffEmployee::getId).toList()).stream()
                .filter(request -> request.getStartAt() != null && request.getStatus() != TimeRequestStatus.CANCELLED)
                .forEach(request -> known.add(request.getEmployee().getId() + "|"
                        + request.getKind().segment().name() + "|" + key(request.getStartAt(), request.getEndAt())));
        notices.findSince(people.stream().map(StaffEmployee::getId).toList(), now.minus(NOTICE_WINDOW).minusDays(1))
                .forEach(notice -> known.add(notice.getEmployee().getId() + "|" + notice.getPart() + "|"
                        + key(notice.getStartAt(), notice.getEndAt())));
        int sent = 0;
        for (Map.Entry<Place, List<StaffEmployee>> place : byPlace.entrySet()) {
            Map<UUID, TimeCardResponse.Row> rows = time.view(place.getKey().storeId(), place.getKey().departmentId(),
                    today.minusDays(1), today, null).rows().stream().filter(row -> !row.civil())
                    .collect(Collectors.toMap(TimeCardResponse.Row::employeeId, row -> row, (a, b) -> a));
            for (StaffEmployee employee : place.getValue()) {
                TimeCardResponse.Row row = rows.get(employee.getId());
                if (row == null) {
                    continue;
                }
                List<Anomaly> fresh = of(row, now).stream()
                        .filter(anomaly -> !Objects.requireNonNullElse(anomaly.end(), anomaly.start())
                                .isBefore(now.minus(NOTICE_WINDOW)))
                        .filter(anomaly -> known.add(employee.getId() + "|" + anomaly.part() + "|"
                                + key(anomaly.start(), anomaly.end())))
                        .toList();
                if (fresh.isEmpty()) {
                    continue;
                }
                fresh.forEach(anomaly -> notices.save(new StaffTimeAnomalyNotice(employee, anomaly.part(),
                        anomaly.start(), anomaly.end())));
                push.notify(List.of(employee.getUser().getId()), "Czas pracy do wyjaśnienia", message(fresh),
                        Map.of("url", "/requests"));
                sent++;
            }
        }
        return sent;
    }

    /** Treść powiadomienia: jedna różnica opisana, kilka policzone. */
    static String message(List<Anomaly> fresh) {
        if (fresh.size() > 1) {
            int count = fresh.size();
            int ones = count % 10;
            int tens = count % 100;
            String word = ones >= 2 && ones <= 4 && (tens < 12 || tens > 14) ? "różnice" : "różnic";
            return "Masz " + count + " " + word + " między odbiciami a grafikiem. Złóż wnioski w aplikacji.";
        }
        Anomaly anomaly = fresh.getFirst();
        String day = DAY.format(anomaly.date());
        return switch (anomaly.part()) {
            case MISSING_EXIT -> "Brak odbicia wyjścia (" + day + ", wejście " + TIME.format(anomaly.start())
                    + "). Zgłoś kierownikowi, o której wyszłaś/wyszedłeś.";
            case "OVERTIME" -> "Praca poza grafikiem: " + day + ", " + TIME.format(anomaly.start()) + "–"
                    + TIME.format(anomaly.end()) + ". Złóż wniosek, żeby liczyła się do czasu pracy.";
            default -> "Brak czasu z grafiku: " + day + ", " + TIME.format(anomaly.start()) + "–"
                    + TIME.format(anomaly.end()) + ". Wyjaśnij to wnioskiem w aplikacji.";
        };
    }

    private static List<TimeRequestKind> kinds(String part) {
        return switch (part) {
            case "OVERTIME" -> OVERTIME_KINDS;
            case "SHORTFALL" -> SHORTFALL_KINDS;
            default -> List.of();
        };
    }

    private static String key(LocalDateTime start, LocalDateTime end) {
        return start + "|" + end;
    }
}
