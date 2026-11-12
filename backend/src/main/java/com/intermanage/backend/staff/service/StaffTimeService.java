package com.intermanage.backend.staff.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.OvertimePdf;
import com.intermanage.backend.staff.component.ScheduleCalendar;
import com.intermanage.backend.staff.component.TimeCard;
import com.intermanage.backend.staff.dto.ScheduleDepartment;
import com.intermanage.backend.staff.dto.TimeCardResponse;
import com.intermanage.backend.staff.dto.TimeRequests;
import com.intermanage.backend.staff.dto.TimeSettingsResponse;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.gus.service.GusService;
import com.intermanage.backend.staff.entity.StaffEmployerDetails;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffNfcCard;
import com.intermanage.backend.staff.entity.StaffScheduleEntry;
import com.intermanage.backend.staff.entity.StaffTimeDecision;
import com.intermanage.backend.staff.entity.StaffTimeDecisionFile;
import com.intermanage.backend.staff.entity.StaffTimePunch;
import com.intermanage.backend.staff.entity.StaffTimeReader;
import com.intermanage.backend.staff.entity.TimeDecisionKind;
import com.intermanage.backend.staff.entity.TimePunchSource;
import com.intermanage.backend.staff.entity.TimeSegmentKind;
import com.intermanage.backend.staff.repository.StaffContractRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentBreakRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentManagerRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentTimeSettingRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffEmployerDetailsRepository;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffNfcCardRepository;
import com.intermanage.backend.staff.repository.StaffScheduleEntryRepository;
import com.intermanage.backend.staff.repository.StaffTimeDecisionFileRepository;
import com.intermanage.backend.staff.repository.StaffTimeDecisionRepository;
import com.intermanage.backend.staff.repository.StaffTimePunchRepository;
import com.intermanage.backend.staff.repository.StaffTimeReaderRepository;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Karty czasu pracy: odbicia kart NFC zestawione z grafikiem, decyzje kierownika o czasie poza grafikiem i brakach,
 * czytniki, karty pracowników i dane pracodawcy do wydruków. Obliczenia dnia robi {@link TimeCard}.
 *
 * <p>Odbicia pracownika idą na zmianę: wejście, wyjście. Para należy do dnia wejścia; wyjście później niż
 * {@link #MAX_SHIFT} po wejściu nie zamyka pary (zapomniane wyjście), a takie wejście zostaje „bez wyjścia”.
 */
@Service
public class StaffTimeService {

    static final Duration MAX_SHIFT = Duration.ofHours(16);
    private static final int MAX_DAYS = 62;
    static final int ATTENTION_DAYS = 31;
    private static final String STAFF_EDIT = "STAFF:EDIT";
    private static final String TIME_EDIT = "STAFF_TIME:EDIT";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StaffEmployeeRepository employees;
    private final StaffDepartmentRepository departments;
    private final StaffDepartmentManagerRepository managers;
    private final StaffDepartmentBreakRepository breaks;
    private final StaffDepartmentTimeSettingRepository timeSettings;
    private final StaffScheduleEntryRepository entries;
    private final StaffContractRepository contracts;
    private final StaffLeaveRepository leaves;
    private final StaffTimePunchRepository punches;
    private final StaffTimeDecisionRepository decisions;
    private final StaffTimeDecisionFileRepository decisionFiles;
    private final StaffTimeReaderRepository readers;
    private final StaffNfcCardRepository cards;
    private final StaffEmployerDetailsRepository employers;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final OvertimePdf overtimePdf;
    private final GusService gus;

    public StaffTimeService(StaffEmployeeRepository employees, StaffDepartmentRepository departments,
            StaffDepartmentManagerRepository managers, StaffDepartmentBreakRepository breaks,
            StaffDepartmentTimeSettingRepository timeSettings, StaffScheduleEntryRepository entries,
            StaffContractRepository contracts, StaffLeaveRepository leaves, StaffTimePunchRepository punches,
            StaffTimeDecisionRepository decisions, StaffTimeDecisionFileRepository decisionFiles,
            StaffTimeReaderRepository readers, StaffNfcCardRepository cards,
            StaffEmployerDetailsRepository employers, StoreRepository stores, AppUserRepository users,
            OvertimePdf overtimePdf, GusService gus) {
        this.employees = employees;
        this.departments = departments;
        this.managers = managers;
        this.breaks = breaks;
        this.timeSettings = timeSettings;
        this.entries = entries;
        this.contracts = contracts;
        this.leaves = leaves;
        this.punches = punches;
        this.decisions = decisions;
        this.decisionFiles = decisionFiles;
        this.readers = readers;
        this.cards = cards;
        this.employers = employers;
        this.stores = stores;
        this.users = users;
        this.overtimePdf = overtimePdf;
        this.gus = gus;
    }

    // ---------------------------------------------------------------- widok

    @Transactional(readOnly = true)
    public List<ScheduleDepartment> departments(UUID storeId, AppUserPrincipal principal) {
        Set<UUID> managed = Set.copyOf(managers.findDepartmentIds(principal.getId()));
        boolean all = canDecideAll(principal);
        boolean edits = has(principal, TIME_EDIT);
        return departments.findActive(store(storeId).companyKey()).stream()
                .map(department -> new ScheduleDepartment(department.getId(), department.getName(),
                        edits && (all || managed.contains(department.getId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public TimeCardResponse view(UUID storeId, UUID departmentId, LocalDate from, LocalDate to,
            AppUserPrincipal principal) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest("Okres od 1 do " + MAX_DAYS + " dni");
        }
        Store store = store(storeId);
        StaffDepartment department = department(store, departmentId);
        int tolerance = tolerance(storeId, departmentId);
        List<TimeCard.Break> unpaid = breaks.findActive(storeId, departmentId).stream()
                .filter(rest -> !rest.isPaid())
                .map(rest -> new TimeCard.Break(rest.getStart(), rest.getEnd())).toList();

        List<StaffEmployee> team = employees.findInStores(List.of(storeId)).stream()
                .filter(employee -> employee.getEndedAt() == null
                        && employee.getPosition().getDepartment().getId().equals(departmentId))
                .toList();
        List<UUID> allIds = team.stream().map(StaffEmployee::getId).toList();
        Map<UUID, List<StaffContract>> contractsOf = allIds.isEmpty() ? Map.of()
                : contracts.findActive(allIds).stream()
                        .collect(Collectors.groupingBy(contract -> contract.getEmployee().getId()));
        Comparator<StaffEmployee> byPosition = Comparator
                .comparing((StaffEmployee employee) -> employee.getPosition().getName().toLowerCase())
                .thenComparing(employee -> employee.getUser().getDisplayName().toLowerCase());
        // Karty czasu pracy prowadzi się dla pracowników z umową o pracę (art. 149 KP), a przy zleceniu ewidencję
        // liczby godzin (art. 8a ustawy o minimalnym wynagrodzeniu): wiersz z samymi odbiciami. Reszta działu (B2B,
        // bez umowy) idzie do listy pod tabelą z powodem, żeby nie znikała bez słowa.
        List<TimeCardResponse.WithoutCard> withoutCard = team.stream()
                .filter(employee -> !hasEmploymentIn(contractsOf.getOrDefault(employee.getId(), List.of()), from, to)
                        && !hasMandateIn(contractsOf.getOrDefault(employee.getId(), List.of()), from, to))
                .sorted(byPosition)
                .map(employee -> new TimeCardResponse.WithoutCard(employee.getUser().getDisplayName(),
                        employee.getPosition().getName(),
                        withoutCardReason(contractsOf.getOrDefault(employee.getId(), List.of()), from, to)))
                .toList();
        Set<UUID> employment = team.stream()
                .filter(employee -> hasEmploymentIn(contractsOf.getOrDefault(employee.getId(), List.of()), from, to))
                .map(StaffEmployee::getId).collect(Collectors.toSet());
        Set<UUID> mandate = team.stream()
                .filter(employee -> hasMandateIn(contractsOf.getOrDefault(employee.getId(), List.of()), from, to))
                .map(StaffEmployee::getId).collect(Collectors.toSet());
        team = team.stream()
                .filter(employee -> employment.contains(employee.getId()) || mandate.contains(employee.getId()))
                .sorted(byPosition)
                .toList();
        List<UUID> ids = team.stream().map(StaffEmployee::getId).toList();

        Map<UUID, Map<LocalDate, StaffScheduleEntry>> planned = new HashMap<>();
        Map<UUID, List<StaffLeave>> absences = new HashMap<>();
        Map<UUID, List<StaffTimePunch>> punched = new HashMap<>();
        Map<UUID, List<StaffTimeDecision>> decided = new HashMap<>();
        Map<UUID, String> cardOf = new HashMap<>();
        Map<UUID, StaffTimeDecisionFileRepository.Summary> fileOf = new HashMap<>();
        Map<UUID, Map<LocalDate, StaffScheduleEntry>> civil = new HashMap<>();
        if (!ids.isEmpty()) {
            entries.findOfEmployees(ids, from, to).forEach(entry -> planned
                    .computeIfAbsent(entry.getEmployee().getId(), key -> new HashMap<>())
                    .putIfAbsent(entry.getWorkDate(), entry));
            entries.findCivilOfEmployees(ids, from, to).forEach(entry -> civil
                    .computeIfAbsent(entry.getEmployee().getId(), key -> new HashMap<>())
                    .putIfAbsent(entry.getWorkDate(), entry));
            leaves.findActive(ids).forEach(leave -> absences
                    .computeIfAbsent(leave.getEmployee().getId(), key -> new ArrayList<>()).add(leave));
            punches.findOfEmployees(ids, instant(from.atStartOfDay().minus(MAX_SHIFT)),
                    instant(to.plusDays(1).atStartOfDay().plus(MAX_SHIFT)))
                    .forEach(punch -> punched.computeIfAbsent(punch.getEmployee().getId(), key -> new ArrayList<>())
                            .add(punch));
            decisions.findActive(ids, from, to).forEach(decision -> decided
                    .computeIfAbsent(decision.getEmployee().getId(), key -> new ArrayList<>()).add(decision));
            cards.findActive(ids).forEach(card -> cardOf.put(card.getEmployee().getId(), card.getCardUid()));
            List<UUID> decisionIds = decided.values().stream().flatMap(List::stream).map(StaffTimeDecision::getId)
                    .toList();
            if (!decisionIds.isEmpty()) {
                decisionFiles.findActiveSummaries(decisionIds).forEach(file -> fileOf.put(file.getDecisionId(), file));
            }
        }

        boolean records = has(principal, AppUserPrincipal.OWNER_AUTHORITY) || has(principal, "STAFF_RECORDS:VIEW")
                || has(principal, "STAFF_RECORDS:EDIT");
        LocalDateTime now = LocalDateTime.now(StoreTime.ZONE);
        List<LocalDate> days = from.datesUntil(to.plusDays(1)).toList();
        List<TimeCardResponse.Row> rows = new ArrayList<>();
        for (StaffEmployee employee : team) {
            UUID id = employee.getId();
            Map<LocalDate, List<Pair>> pairs = pairs(punched.getOrDefault(id, List.of())).stream()
                    .collect(Collectors.groupingBy(pair -> pair.in().toLocalDate()));
            boolean employed = employment.contains(id);
            if (employed) {
                List<TimeCardResponse.DayCard> cardsOfDays = new ArrayList<>();
                for (LocalDate day : days) {
                    cardsOfDays.add(dayCard(day, planned.getOrDefault(id, Map.of()).get(day),
                            civil.getOrDefault(id, Map.of()).get(day),
                            leaveOn(absences.getOrDefault(id, List.of()), day), pairs.getOrDefault(day, List.of()),
                            decided.getOrDefault(id, List.of()), fileOf, unpaid, tolerance, records, now));
                }
                rows.add(new TimeCardResponse.Row(id, employee.getUser().getDisplayName(),
                        employee.getPosition().getName(), cardOf.get(id), employee.getRcpNumber(), false,
                        cardsOfDays, totals(cardsOfDays)));
            }
            if (mandate.contains(id)) {
                // Przy obu umowach godziny zlecenia to odbicia w jego godzinach z grafiku (wiersz „↳ zlecenie”),
                // a samo zlecenie bierze wszystkie odbicia i zwykłą zmianę z grafiku jako ramkę.
                List<TimeCardResponse.DayCard> cardsOfDays = new ArrayList<>();
                for (LocalDate day : days) {
                    StaffLeave leave = leaveOn(absences.getOrDefault(id, List.of()), day);
                    StaffScheduleEntry entry = employed ? civil.getOrDefault(id, Map.of()).get(day)
                            : planned.getOrDefault(id, Map.of()).get(day);
                    cardsOfDays.add(civilDayCard(day, entry, employed, leave == null ? null : absence(leave, records),
                            pairs.getOrDefault(day, List.of()), now));
                }
                rows.add(new TimeCardResponse.Row(id, employee.getUser().getDisplayName(),
                        employee.getPosition().getName(), cardOf.get(id), employee.getRcpNumber(), true,
                        cardsOfDays, totals(cardsOfDays)));
            }
        }
        return new TimeCardResponse(departmentId, from, to, canDecide(department, principal), canConfigure(principal),
                has(principal, AppUserPrincipal.OWNER_AUTHORITY), tolerance,
                days.stream().map(day -> new TimeCardResponse.Day(day, ScheduleCalendar.holiday(day))).toList(), rows,
                withoutCard);
    }

    private static boolean hasEmploymentIn(List<StaffContract> contracts, LocalDate from, LocalDate to) {
        return contracts.stream().anyMatch(contract -> contract.isEmployment() && overlaps(contract, from, to));
    }

    private static boolean hasMandateIn(List<StaffContract> contracts, LocalDate from, LocalDate to) {
        return contracts.stream()
                .anyMatch(contract -> contract.getKind() == ContractKind.MANDATE && overlaps(contract, from, to));
    }

    /**
     * Dzień ewidencji godzin zlecenia: same odbicia jako praca (bez przerw, tolerancji, nadgodzin i braków), grafik
     * tylko do porównania. {@code alongside}: osoba ma też umowę o pracę, więc liczą się tylko odbicia w godzinach
     * zlecenia z grafiku, a wejście bez wyjścia pokazuje karta umowy o pracę.
     */
    private TimeCardResponse.DayCard civilDayCard(LocalDate day, StaffScheduleEntry entry, boolean alongside,
            String absence, List<Pair> pairs, LocalDateTime now) {
        TimeCard.Interval shift = entry == null ? null : shift(entry);
        List<TimeCard.Interval> closed = pairs.stream().filter(pair -> pair.out() != null)
                .map(pair -> new TimeCard.Interval(pair.in(), pair.out())).toList();
        List<TimeCard.Interval> worked = !alongside ? closed : shift == null ? List.of()
                : closed.stream()
                        .filter(interval -> interval.end().isAfter(shift.start())
                                && interval.start().isBefore(shift.end()))
                        .map(interval -> new TimeCard.Interval(
                                interval.start().isBefore(shift.start()) ? shift.start() : interval.start(),
                                interval.end().isAfter(shift.end()) ? shift.end() : interval.end()))
                        .toList();
        List<TimeCardResponse.Segment> segments = worked.stream()
                .map(interval -> new TimeCardResponse.Segment(TimeCard.Part.WORK.name(), interval.start(),
                        interval.end(), minutes(interval), TimeCard.Status.OK.name(), null, null, null, null))
                .toList();
        Pair open = alongside ? null : pairs.stream().filter(pair -> pair.out() == null).findFirst().orElse(null);
        boolean missingExit = open != null && Duration.between(open.in(), now).compareTo(MAX_SHIFT) > 0;
        List<TimeCardResponse.Punch> punchList = pairs.stream()
                .flatMap(pair -> pair.out() == null ? java.util.stream.Stream.of(pair.inPunch())
                        : java.util.stream.Stream.of(pair.inPunch(), pair.outPunch()))
                .filter(punch -> !alongside || shift != null && !local(punch.getPunchedAt()).isBefore(shift.start())
                        && !local(punch.getPunchedAt()).isAfter(shift.end()))
                .map(punch -> new TimeCardResponse.Punch(punch.getId(), local(punch.getPunchedAt()),
                        punch.getSource().name()))
                .toList();
        int workedMinutes = segments.stream().mapToInt(TimeCardResponse.Segment::minutes).sum();
        return new TimeCardResponse.DayCard(day, shift == null ? null : shift.start(),
                shift == null ? null : shift.end(), absence, punchList, open == null ? null : open.in(), missingExit,
                segments, shift == null ? 0 : minutes(shift), workedMinutes, 0, 0, 0, null, null);
    }

    private static int minutes(TimeCard.Interval interval) {
        return (int) Duration.between(interval.start(), interval.end()).toMinutes();
    }

    /** Dlaczego osoba z działu nie ma karty w tym okresie. */
    private static String withoutCardReason(List<StaffContract> contracts, LocalDate from, LocalDate to) {
        List<StaffContract> inPeriod = contracts.stream().filter(contract -> overlaps(contract, from, to)).toList();
        if (inPeriod.stream().anyMatch(contract -> contract.getKind() == ContractKind.MANDATE)) {
            return "umowa zlecenie";
        }
        if (inPeriod.stream().anyMatch(contract -> contract.getKind() == ContractKind.B2B)) {
            return "B2B";
        }
        return contracts.isEmpty() ? "brak umowy w Kadrach" : "brak umowy w tym okresie";
    }

    private TimeCardResponse.DayCard dayCard(LocalDate day, StaffScheduleEntry entry, StaffScheduleEntry civilEntry,
            StaffLeave leave,
            List<Pair> pairs, List<StaffTimeDecision> decisionsOfEmployee,
            Map<UUID, StaffTimeDecisionFileRepository.Summary> fileOf, List<TimeCard.Break> unpaid, int tolerance,
            boolean records, LocalDateTime now) {
        TimeCard.Interval shift = entry == null ? null : shift(entry);
        // Dzień nieobecności nie jest dniem pracy: odbicia tego dnia to czas poza grafikiem.
        TimeCard.Interval plannedShift = leave == null ? shift : null;
        TimeCard.Interval civilShift = civilEntry == null ? null : shift(civilEntry);
        List<TimeCard.Interval> worked = withoutCivil(pairs.stream().filter(pair -> pair.out() != null)
                .map(pair -> new TimeCard.Interval(pair.in(), pair.out())).toList(), civilShift);
        List<StaffTimeDecision> ofDay = decisionsOfEmployee.stream()
                .filter(decision -> decision.getWorkDate().equals(day)).toList();
        TimeCard.Day computed = TimeCard.day(day, plannedShift, worked, unpaid, tolerance, ofDay.stream()
                .map(decision -> new TimeCard.Decision(decision.getKind(), decision.getStartAt(), decision.getEndAt(),
                        decision.getDecision()))
                .toList());
        Pair open = pairs.stream().filter(pair -> pair.out() == null).findFirst().orElse(null);
        boolean missingExit = open != null && Duration.between(open.in(), now).compareTo(MAX_SHIFT) > 0;
        StaffTimeDecision balance = ofDay.stream().filter(decision -> decision.getKind() == TimeSegmentKind.BALANCE)
                .findFirst().orElse(null);
        List<TimeCardResponse.Segment> segments = computed.segments().stream().map(segment -> {
            boolean offset = segment.status() == TimeCard.Status.OFFSET_OVERTIME
                    || segment.status() == TimeCard.Status.OFFSET_SHORTFALL;
            TimeSegmentKind kind = segment.part() == TimeCard.Part.OVERTIME ? TimeSegmentKind.OVERTIME
                    : segment.part() == TimeCard.Part.SHORTFALL ? TimeSegmentKind.SHORTFALL : null;
            StaffTimeDecision decision = offset ? balance : kind == null ? null : ofDay.stream()
                    .filter(candidate -> candidate.getKind() == kind && candidate.getStartAt().equals(segment.start())
                            && candidate.getEndAt().equals(segment.end()))
                    .findFirst().orElse(null);
            StaffTimeDecisionFileRepository.Summary file = decision == null ? null : fileOf.get(decision.getId());
            return new TimeCardResponse.Segment(segment.part().name(), segment.start(), segment.end(),
                    segment.minutes(), segment.status().name(), decision == null ? null : decision.getId(),
                    decision == null || decision.getCreatedBy() == null ? null
                            : decision.getCreatedBy().getDisplayName(),
                    decision == null ? null : decision.getNote(),
                    file == null ? null : new TimeCardResponse.SignedFile(file.getId(), file.getFileName()));
        }).toList();
        List<TimeCardResponse.Punch> punchList = pairs.stream()
                .flatMap(pair -> pair.out() == null ? java.util.stream.Stream.of(pair.inPunch())
                        : java.util.stream.Stream.of(pair.inPunch(), pair.outPunch()))
                .map(punch -> new TimeCardResponse.Punch(punch.getId(), local(punch.getPunchedAt()),
                        punch.getSource().name()))
                .toList();
        return new TimeCardResponse.DayCard(day, shift == null ? null : shift.start(),
                shift == null ? null : shift.end(), leave == null ? null : absence(leave, records), punchList,
                open == null ? null : open.in(), missingExit, segments, computed.planned(), computed.worked(),
                computed.overtime(), computed.pending(), computed.shortfall(),
                civilShift == null ? null : civilShift.start(), civilShift == null ? null : civilShift.end());
    }

    /**
     * Odbicia w godzinach zlecenia z grafiku to praca na zleceniu: nie wchodzą do karty umowy o pracę i nie czekają jako
     * nadgodziny. Grafik ostrzega o łączeniu obu umów osobno.
     */
    static List<TimeCard.Interval> withoutCivil(List<TimeCard.Interval> worked, TimeCard.Interval civil) {
        if (civil == null) {
            return worked;
        }
        List<TimeCard.Interval> result = new ArrayList<>();
        for (TimeCard.Interval interval : worked) {
            if (!interval.end().isAfter(civil.start()) || !interval.start().isBefore(civil.end())) {
                result.add(interval);
                continue;
            }
            if (interval.start().isBefore(civil.start())) {
                result.add(new TimeCard.Interval(interval.start(), civil.start()));
            }
            if (interval.end().isAfter(civil.end())) {
                result.add(new TimeCard.Interval(civil.end(), interval.end()));
            }
        }
        return result;
    }

    private static TimeCardResponse.Totals totals(List<TimeCardResponse.DayCard> days) {
        return new TimeCardResponse.Totals(days.stream().mapToInt(TimeCardResponse.DayCard::planned).sum(),
                days.stream().mapToInt(TimeCardResponse.DayCard::worked).sum(),
                days.stream().mapToInt(TimeCardResponse.DayCard::overtime).sum(),
                days.stream().mapToInt(TimeCardResponse.DayCard::pending).sum(),
                days.stream().mapToInt(TimeCardResponse.DayCard::shortfall).sum());
    }

    /**
     * Sprawy do reakcji kierownika: dni pracowników z ostatnich {@link #ATTENTION_DAYS} dni (do dziś) z czasem
     * czekającym na decyzję, brakiem albo brakiem wyjścia, w działach, o których użytkownik decyduje. Licznik w menu.
     */
    @Transactional(readOnly = true)
    public Attention attention(UUID storeId, AppUserPrincipal principal) {
        LocalDate today = LocalDate.now(StoreTime.ZONE);
        LocalDate from = today.minusDays(ATTENTION_DAYS - 1);
        List<Attention.Department> result = new ArrayList<>();
        for (ScheduleDepartment department : departments(storeId, principal)) {
            if (!department.canEdit()) {
                continue;
            }
            int count = (int) view(storeId, department.id(), from, today, principal).rows().stream()
                    .flatMap(row -> row.days().stream())
                    .filter(day -> day.pending() > 0 || day.shortfall() > 0 || day.missingExit())
                    .count();
            if (count > 0) {
                result.add(new Attention.Department(department.id(), count));
            }
        }
        return new Attention(result.stream().mapToInt(Attention.Department::count).sum(), result);
    }

    public record Attention(int total, List<Department> departments) {

        public record Department(UUID departmentId, int count) {
        }
    }

    // ---------------------------------------------------------------- decyzje

    @Transactional
    public UUID decide(UUID storeId, UUID departmentId, TimeRequests.Decision request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(store, departmentId);
        requireDecide(department, principal);
        boolean overtime = request.kind() == TimeSegmentKind.OVERTIME;
        boolean balance = request.kind() == TimeSegmentKind.BALANCE;
        if (balance != (request.decision() == TimeDecisionKind.OFFSET)) {
            throw ApiException.badRequest("Zrównoważyć można tylko cały dzień");
        }
        if (!balance && overtime == (request.decision() == TimeDecisionKind.JUSTIFIED)) {
            throw ApiException.badRequest(overtime ? "Czas poza grafikiem: polecenie, wniosek albo odrzucenie"
                    : "Brak czasu można tylko usprawiedliwić");
        }
        if (!request.end().isAfter(request.start())) {
            throw ApiException.badRequest("Koniec odcinka musi być po początku");
        }
        StaffEmployee employee = employeeOf(store, department, request.employeeId());
        LocalDate day = request.start().toLocalDate();
        // Odcinek musi być w karcie: decyzja dotyczy tego, co widać, a nie dowolnych godzin.
        TimeCardResponse.DayCard card = view(storeId, departmentId, day, day, principal).rows().stream()
                .filter(row -> !row.civil() && row.employeeId().equals(employee.getId())).findFirst()
                .map(row -> row.days().getFirst())
                .orElseThrow(() -> ApiException.notFound("Pracownik nie ma karty czasu pracy w tym dziale"));
        if (balance) {
            // Zrównoważenie dotyczy całego dnia: od północy do północy, gdy dzień ma i brak, i czas poza grafikiem.
            boolean wholeDay = request.start().equals(day.atStartOfDay())
                    && request.end().equals(day.plusDays(1).atStartOfDay());
            boolean both = card.segments().stream().anyMatch(segment -> segment.status().equals("UNEXCUSED"))
                    && card.segments().stream().anyMatch(segment -> segment.status().equals("PENDING"));
            if (!wholeDay || !both) {
                throw ApiException.conflict("Ten dzień nie ma braku i czasu poza grafikiem do zrównoważenia");
            }
        } else {
            boolean exists = card.segments().stream().anyMatch(segment -> segment.part().equals(request.kind().name())
                    && segment.start().equals(request.start()) && segment.end().equals(request.end()));
            if (!exists) {
                throw ApiException.conflict("Tego odcinka nie ma już w karcie. Odśwież stronę.");
            }
        }
        AppUser actor = users.getReferenceById(principal.getId());
        decisions.findActive(List.of(employee.getId()), day, day).stream()
                .filter(decision -> decision.getKind() == request.kind() && decision.getStartAt().equals(request.start())
                        && decision.getEndAt().equals(request.end()))
                .forEach(decision -> decision.markDeleted(actor));
        decisions.flush();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        return decisions.saveAndFlush(new StaffTimeDecision(employee, day, request.kind(), request.start(),
                request.end(), request.decision(), note, actor)).getId();
    }

    @Transactional
    public void removeDecision(UUID storeId, UUID decisionId, AppUserPrincipal principal) {
        StaffTimeDecision decision = decision(storeId, decisionId, principal);
        decision.markDeleted(users.getReferenceById(principal.getId()));
    }

    @Transactional
    public void restoreDecision(UUID storeId, UUID decisionId, AppUserPrincipal principal) {
        StaffTimeDecision decision = decision(storeId, decisionId, principal);
        boolean taken = decisions.findActive(List.of(decision.getEmployee().getId()), decision.getWorkDate(),
                decision.getWorkDate()).stream()
                .anyMatch(other -> other.getKind() == decision.getKind() && other.getStartAt().equals(
                        decision.getStartAt()) && other.getEndAt().equals(decision.getEndAt()));
        if (taken) {
            throw ApiException.conflict("Ten odcinek ma już nowszą decyzję");
        }
        decision.restore();
    }

    @Transactional(readOnly = true)
    public PdfFile decisionPdf(UUID storeId, UUID decisionId, AppUserPrincipal principal) {
        StaffTimeDecision decision = decisions.findById(decisionId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono decyzji"));
        Store store = store(storeId);
        StaffEmployee employee = decision.getEmployee();
        if (!employee.getStore().companyKey().equals(store.companyKey())) {
            throw ApiException.notFound("Nie znaleziono decyzji");
        }
        OvertimePdf.Variant variant = switch (decision.getDecision()) {
            case EMPLOYER_ORDER -> OvertimePdf.Variant.EMPLOYER_ORDER;
            case EMPLOYEE_REQUEST -> OvertimePdf.Variant.EMPLOYEE_REQUEST;
            default -> throw ApiException.badRequest("Wydruk jest tylko dla nadgodzin na polecenie albo na wniosek");
        };
        TimeSettingsResponse.Employer employer = employer(store);
        if (employer.name() == null || employer.name().isBlank()) {
            throw ApiException.conflict("Uzupełnij nazwę firmy w danych pracodawcy (Czas pracy, koło zębate): "
                    + "na wydruku musi być pracodawca, a nie nazwa sklepu w aplikacji");
        }
        byte[] content = overtimePdf.pdf(new OvertimePdf.Document(variant,
                new OvertimePdf.Employer(employer.name(), employer.address(), employer.nip(), employer.regon(),
                        employer.representative()),
                employee.getUser().getDisplayName(), employee.getPosition().getName(),
                employee.getPosition().getDepartment().getName(), decision.getStartAt(), decision.getEndAt(),
                decision.getNote(), decision.getCreatedBy() == null ? null : decision.getCreatedBy().getDisplayName(),
                local(decision.getCreatedAt())));
        String name = (variant == OvertimePdf.Variant.EMPLOYER_ORDER ? "polecenie" : "wniosek") + "-nadgodziny-"
                + decision.getWorkDate() + ".pdf";
        return new PdfFile(name, content);
    }

    public record PdfFile(String fileName, byte[] content) {
    }

    /** Plik z treścią i typem (podpisane polecenie albo wniosek). */
    public record StoredFile(String fileName, String contentType, byte[] content) {
    }

    /**
     * Dołącza podpisane polecenie albo wniosek o nadgodziny (skan albo zdjęcie). Poprzedni plik dostaje znacznik, więc
     * wymiana zostawia historię. Zwraca id nowego pliku.
     */
    @Transactional
    public UUID attachFile(UUID storeId, UUID decisionId, MultipartFile file, AppUserPrincipal principal) {
        StaffTimeDecision decision = decision(storeId, decisionId, principal);
        requireSigned(decision);
        byte[] content = ScanFiles.content(file);
        String contentType = ScanFiles.contentType(content);
        if (contentType == null) {
            throw ApiException.badRequest("Dołącz PDF albo zdjęcie (JPG, PNG, WEBP)");
        }
        AppUser actor = users.getReferenceById(principal.getId());
        decisionFiles.findActive(decisionId).ifPresent(previous -> previous.markRemoved(actor));
        decisionFiles.flush();
        String name = ScanFiles.fileName(file, contentType);
        return decisionFiles.saveAndFlush(new StaffTimeDecisionFile(decision, name, contentType, content, actor))
                .getId();
    }

    @Transactional(readOnly = true)
    public StoredFile signedFile(UUID storeId, UUID decisionId) {
        Store store = store(storeId);
        StaffTimeDecisionFile file = decisionFiles.findActive(decisionId)
                .filter(found -> found.getDecision().getEmployee().getStore().companyKey().equals(store.companyKey()))
                .orElseThrow(() -> ApiException.notFound("Ta decyzja nie ma dołączonego pliku"));
        return new StoredFile(file.getFileName(), file.getContentType(), file.getContent());
    }

    /** Usunięcie dołączonego pliku to znacznik; przywraca go {@link #restoreFile}. */
    @Transactional
    public void removeFile(UUID storeId, UUID decisionId, AppUserPrincipal principal) {
        decision(storeId, decisionId, principal);
        StaffTimeDecisionFile file = decisionFiles.findActive(decisionId)
                .orElseThrow(() -> ApiException.notFound("Ta decyzja nie ma dołączonego pliku"));
        file.markRemoved(users.getReferenceById(principal.getId()));
    }

    @Transactional
    public void restoreFile(UUID storeId, UUID decisionId, UUID fileId, AppUserPrincipal principal) {
        decision(storeId, decisionId, principal);
        StaffTimeDecisionFile file = decisionFiles.findById(fileId)
                .filter(found -> found.getDecision().getId().equals(decisionId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pliku"));
        if (file.getRemovedAt() == null) {
            return;
        }
        if (decisionFiles.findActive(decisionId).isPresent()) {
            throw ApiException.conflict("Decyzja ma już inny dołączony plik");
        }
        file.restore();
    }

    private static void requireSigned(StaffTimeDecision decision) {
        if (decision.getDeletedAt() != null) {
            throw ApiException.conflict("Ta decyzja została cofnięta");
        }
        if (decision.getDecision() != TimeDecisionKind.EMPLOYER_ORDER
                && decision.getDecision() != TimeDecisionKind.EMPLOYEE_REQUEST) {
            throw ApiException.badRequest("Plik dołącza się tylko do polecenia albo wniosku o nadgodziny");
        }
    }

    private StaffTimeDecision decision(UUID storeId, UUID decisionId, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffTimeDecision decision = decisions.findById(decisionId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono decyzji"));
        StaffEmployee employee = decision.getEmployee();
        if (!employee.getStore().companyKey().equals(store.companyKey())) {
            throw ApiException.notFound("Nie znaleziono decyzji");
        }
        requireDecide(employee.getPosition().getDepartment(), principal);
        return decision;
    }

    // ---------------------------------------------------------------- odbicia

    /** Symulator w panelu (tylko właściciel): odbicie tak, jakby przyłożono kartę do czytnika. */
    @Transactional
    public void simulate(UUID storeId, TimeRequests.SimulatedPunch request, AppUserPrincipal principal) {
        if (!has(principal, AppUserPrincipal.OWNER_AUTHORITY)) {
            throw ApiException.forbidden("Symulator odbić jest tylko dla właściciela");
        }
        Store store = store(storeId);
        StaffEmployee employee = employees.findWithDetails(request.employeeId())
                .filter(found -> found.getStore().companyKey().equals(store.companyKey()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
        Instant at = request.at() == null ? Instant.now() : instant(request.at());
        punches.save(new StaffTimePunch(employee, store, at, TimePunchSource.SIMULATED, null,
                users.getReferenceById(principal.getId())));
    }

    /** Usunięcie odbicia z symulatora (znacznik); odbić z czytnika się nie usuwa. */
    @Transactional
    public void removePunch(UUID storeId, UUID punchId, AppUserPrincipal principal) {
        if (!has(principal, AppUserPrincipal.OWNER_AUTHORITY)) {
            throw ApiException.forbidden("Odbicia z symulatora usuwa tylko właściciel");
        }
        StaffTimePunch punch = punches.findById(punchId)
                .filter(found -> found.getDeletedAt() == null && found.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono odbicia"));
        if (punch.getSource() != TimePunchSource.SIMULATED) {
            throw ApiException.conflict("Odbić z czytnika się nie usuwa");
        }
        punch.markDeleted(users.getReferenceById(principal.getId()));
    }

    /**
     * Czas pracy z kart, dzień po dniu, do salda okresu w grafiku: tylko dni, które minęły (bez dziś, bo zmiana trwa), od
     * dnia pierwszego odbicia pracownika, bo wcześniej kart nie było i liczy się grafik. Przerwy i tolerancja są działu
     * i sklepu, w którym tego dnia była zmiana, a bez zmiany działu i sklepu pracownika, jak w karcie czasu pracy.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Map<LocalDate, Integer>> countedMinutes(List<StaffEmployee> team, LocalDate from, LocalDate to) {
        LocalDate yesterday = LocalDate.now(StoreTime.ZONE).minusDays(1);
        LocalDate until = to.isAfter(yesterday) ? yesterday : to;
        if (team.isEmpty() || until.isBefore(from)) {
            return Map.of();
        }
        Map<UUID, LocalDate> firstDay = new HashMap<>();
        punches.findFirst(team.stream().map(StaffEmployee::getId).toList())
                .forEach(first -> firstDay.put(first.getEmployeeId(), local(first.getPunchedAt()).toLocalDate()));
        List<StaffEmployee> tracked = team.stream().filter(employee -> firstDay.containsKey(employee.getId())
                && !firstDay.get(employee.getId()).isAfter(until)).toList();
        if (tracked.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = tracked.stream().map(StaffEmployee::getId).toList();
        Map<UUID, Map<LocalDate, StaffScheduleEntry>> planned = new HashMap<>();
        entries.findOfEmployees(ids, from, until).forEach(entry -> planned
                .computeIfAbsent(entry.getEmployee().getId(), key -> new HashMap<>())
                .putIfAbsent(entry.getWorkDate(), entry));
        Map<UUID, Map<LocalDate, StaffScheduleEntry>> civil = new HashMap<>();
        entries.findCivilOfEmployees(ids, from, until).forEach(entry -> civil
                .computeIfAbsent(entry.getEmployee().getId(), key -> new HashMap<>())
                .putIfAbsent(entry.getWorkDate(), entry));
        Map<UUID, List<StaffLeave>> absences = leaves.findActive(ids).stream()
                .collect(Collectors.groupingBy(leave -> leave.getEmployee().getId()));
        Map<UUID, List<StaffTimePunch>> punched = punches.findOfEmployees(ids,
                instant(from.atStartOfDay().minus(MAX_SHIFT)), instant(until.plusDays(1).atStartOfDay().plus(MAX_SHIFT)))
                .stream().collect(Collectors.groupingBy(punch -> punch.getEmployee().getId()));
        Map<UUID, List<StaffTimeDecision>> decided = decisions.findActive(ids, from, until).stream()
                .collect(Collectors.groupingBy(decision -> decision.getEmployee().getId()));

        record Place(UUID storeId, UUID departmentId) {
        }
        record Rules(List<TimeCard.Break> unpaid, int tolerance) {
        }
        Map<Place, Rules> rulesOf = new HashMap<>();
        Map<UUID, Map<LocalDate, Integer>> result = new HashMap<>();
        for (StaffEmployee employee : tracked) {
            UUID id = employee.getId();
            Map<LocalDate, List<Pair>> pairs = pairs(punched.getOrDefault(id, List.of())).stream()
                    .collect(Collectors.groupingBy(pair -> pair.in().toLocalDate()));
            LocalDate start = firstDay.get(id).isAfter(from) ? firstDay.get(id) : from;
            Map<LocalDate, Integer> days = new HashMap<>();
            for (LocalDate day = start; !day.isAfter(until); day = day.plusDays(1)) {
                StaffScheduleEntry entry = planned.getOrDefault(id, Map.of()).get(day);
                Place place = entry == null
                        ? new Place(employee.getStore().getId(), employee.getPosition().getDepartment().getId())
                        : new Place(entry.getSchedule().getStore().getId(), entry.getSchedule().getDepartment().getId());
                Rules rules = rulesOf.computeIfAbsent(place, key -> new Rules(
                        breaks.findActive(key.storeId(), key.departmentId()).stream().filter(rest -> !rest.isPaid())
                                .map(rest -> new TimeCard.Break(rest.getStart(), rest.getEnd())).toList(),
                        tolerance(key.storeId(), key.departmentId())));
                StaffLeave leave = leaveOn(absences.getOrDefault(id, List.of()), day);
                TimeCard.Interval shift = entry == null || leave != null ? null : shift(entry);
                StaffScheduleEntry civilEntry = civil.getOrDefault(id, Map.of()).get(day);
                List<TimeCard.Interval> worked = withoutCivil(pairs.getOrDefault(day, List.of()).stream()
                        .filter(pair -> pair.out() != null).map(pair -> new TimeCard.Interval(pair.in(), pair.out()))
                        .toList(), civilEntry == null ? null : shift(civilEntry));
                LocalDate current = day;
                List<TimeCard.Decision> ofDay = decided.getOrDefault(id, List.of()).stream()
                        .filter(decision -> decision.getWorkDate().equals(current))
                        .map(decision -> new TimeCard.Decision(decision.getKind(), decision.getStartAt(),
                                decision.getEndAt(), decision.getDecision()))
                        .toList();
                days.put(day, TimeCard.day(day, shift, worked, rules.unpaid(), rules.tolerance(), ofDay).worked());
            }
            result.put(id, days);
        }
        return result;
    }

    /** Odbicie z czytnika NFC: czytnik przedstawia się kluczem, karta wskazuje pracownika firmy. */
    @Transactional
    public ReaderResult readerPunch(String token, TimeRequests.ReaderPunch request) {
        StaffTimeReader reader = reader(token);
        Store store = reader.getStore();
        StaffNfcCard card = cards.findByUid(normalizeUid(request.cardUid()))
                .filter(found -> found.getEmployee().getStore().companyKey().equals(store.companyKey())
                        && found.getEmployee().getEndedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nieznana karta"));
        StaffEmployee employee = card.getEmployee();
        Instant at = request.punchedAt() == null ? Instant.now() : request.punchedAt();
        List<Pair> recent = pairs(punches.findOfEmployees(List.of(employee.getId()), at.minus(MAX_SHIFT), at));
        boolean exit = recent.stream().anyMatch(pair -> pair.out() == null
                && Duration.between(pair.in(), local(at)).compareTo(MAX_SHIFT) <= 0);
        punches.save(new StaffTimePunch(employee, store, at, TimePunchSource.READER, reader, null));
        return new ReaderResult(employee.getUser().getDisplayName(), exit ? "OUT" : "IN", at);
    }

    public record ReaderResult(String employeeName, String direction, Instant punchedAt) {
    }

    /** Czytnik (np. kiosk) sprawdza swój klucz i dowiaduje się, w którym sklepie stoi. */
    @Transactional(readOnly = true)
    public ReaderInfo readerInfo(String token) {
        StaffTimeReader reader = reader(token);
        Store store = reader.getStore();
        return new ReaderInfo(reader.getName(), store.getId(), store.getNumber(), store.getName());
    }

    public record ReaderInfo(String readerName, UUID storeId, String storeNumber, String storeName) {
    }

    /** Czytnik po kluczu; usunięty w Ustawieniach przestaje działać od razu. */
    private StaffTimeReader reader(String token) {
        return (token == null ? Optional.<StaffTimeReader>empty() : readers.findByToken(hash(token)))
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.forbidden("Nieznany czytnik"));
    }

    /** Wejście i wyjście; wyjście {@code null}, gdy go nie ma (jeszcze w pracy albo zapomniane). */
    record Pair(StaffTimePunch inPunch, StaffTimePunch outPunch) {

        LocalDateTime in() {
            return local(inPunch.getPunchedAt());
        }

        LocalDateTime out() {
            return outPunch == null ? null : local(outPunch.getPunchedAt());
        }
    }

    static List<Pair> pairs(List<StaffTimePunch> punchesOfEmployee) {
        List<StaffTimePunch> sorted = punchesOfEmployee.stream()
                .sorted(Comparator.comparing(StaffTimePunch::getPunchedAt)).toList();
        List<Pair> result = new ArrayList<>();
        StaffTimePunch open = null;
        for (StaffTimePunch punch : sorted) {
            if (open == null) {
                open = punch;
            } else if (Duration.between(open.getPunchedAt(), punch.getPunchedAt()).compareTo(MAX_SHIFT) <= 0) {
                if (punch.getPunchedAt().isAfter(open.getPunchedAt())) {
                    result.add(new Pair(open, punch));
                }
                open = null;
            } else {
                result.add(new Pair(open, null));
                open = punch;
            }
        }
        if (open != null) {
            result.add(new Pair(open, null));
        }
        return result;
    }

    // ---------------------------------------------------------------- ustawienia

    @Transactional(readOnly = true)
    public TimeSettingsResponse settings(UUID storeId) {
        Store store = store(storeId);
        List<StaffEmployee> staff = employees.findInStores(List.of(storeId)).stream()
                .filter(employee -> employee.getEndedAt() == null)
                .sorted(Comparator.comparing(employee -> employee.getUser().getDisplayName().toLowerCase()))
                .toList();
        Map<UUID, String> cardOf = staff.isEmpty() ? Map.of()
                : cards.findActive(staff.stream().map(StaffEmployee::getId).toList()).stream()
                        .collect(Collectors.toMap(card -> card.getEmployee().getId(), StaffNfcCard::getCardUid));
        return new TimeSettingsResponse(employer(store),
                readers.findActive(storeId).stream()
                        .map(reader -> new TimeSettingsResponse.Reader(reader.getId(), reader.getName(),
                                reader.getCreatedAt(), null))
                        .toList(),
                staff.stream().map(employee -> new TimeSettingsResponse.Card(employee.getId(),
                        employee.getUser().getDisplayName(), employee.getPosition().getDepartment().getName(),
                        cardOf.get(employee.getId()), employee.getRcpNumber())).toList());
    }

    @Transactional
    public TimeSettingsResponse saveEmployer(UUID storeId, TimeRequests.Employer request, UUID actorId) {
        Store store = store(storeId);
        AppUser actor = users.getReferenceById(actorId);
        String nip = blank(request.nip());
        if (nip != null && !nip.matches("\\d{10}")) {
            throw ApiException.badRequest("NIP to 10 cyfr");
        }
        String regon = blank(request.regon());
        if (regon != null && !regon.matches("\\d{9}|\\d{14}")) {
            throw ApiException.badRequest("REGON to 9 albo 14 cyfr");
        }
        employers.findActive(store.companyKey()).ifPresent(current -> current.markDeleted(actor));
        employers.flush();
        employers.save(new StaffEmployerDetails(store.companyKey(), request.name().strip(), blank(request.address()),
                nip, regon, blank(request.representative()), actor));
        employers.flush();
        return settings(storeId);
    }

    @Transactional
    public TimeSettingsResponse addReader(UUID storeId, TimeRequests.Reader request, UUID actorId) {
        Store store = store(storeId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StaffTimeReader reader = readers.saveAndFlush(new StaffTimeReader(store, request.name().strip(), hash(token),
                users.getReferenceById(actorId)));
        TimeSettingsResponse current = settings(storeId);
        return new TimeSettingsResponse(current.employer(), current.readers().stream()
                .map(item -> item.id().equals(reader.getId())
                        ? new TimeSettingsResponse.Reader(item.id(), item.name(), item.createdAt(), token)
                        : item)
                .toList(), current.cards());
    }

    @Transactional
    public TimeSettingsResponse removeReader(UUID storeId, UUID readerId, UUID actorId) {
        StaffTimeReader reader = readers.findById(readerId)
                .filter(found -> found.getDeletedAt() == null && found.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono czytnika"));
        reader.markDeleted(users.getReferenceById(actorId));
        return settings(storeId);
    }

    @Transactional
    public TimeSettingsResponse restoreReader(UUID storeId, UUID readerId) {
        StaffTimeReader reader = readers.findById(readerId)
                .filter(found -> found.getDeletedAt() != null && found.getStore().getId().equals(storeId))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono czytnika"));
        reader.restore();
        return settings(storeId);
    }

    @Transactional
    public TimeSettingsResponse saveCard(UUID storeId, UUID employeeId, TimeRequests.Card request, UUID actorId) {
        StaffEmployee employee = storeEmployee(storeId, employeeId);
        AppUser actor = users.getReferenceById(actorId);
        String uid = normalizeUid(request.cardUid());
        cards.findByUid(uid).filter(card -> !card.getEmployee().getId().equals(employeeId)).ifPresent(card -> {
            throw ApiException.conflict("Ta karta jest już przypisana: " + card.getEmployee().getUser()
                    .getDisplayName());
        });
        List<StaffNfcCard> current = cards.findActive(List.of(employeeId));
        if (current.stream().anyMatch(card -> card.getCardUid().equals(uid))) {
            return settings(storeId);
        }
        current.forEach(card -> card.markDeleted(actor));
        cards.flush();
        cards.saveAndFlush(new StaffNfcCard(employee, uid, actor));
        return settings(storeId);
    }

    @Transactional
    public TimeSettingsResponse removeCard(UUID storeId, UUID employeeId, UUID actorId) {
        storeEmployee(storeId, employeeId);
        AppUser actor = users.getReferenceById(actorId);
        cards.findActive(List.of(employeeId)).forEach(card -> card.markDeleted(actor));
        return settings(storeId);
    }

    /**
     * Dane pracodawcy na wydruki: zapisane dla firmy, a bez nich podpowiedź z GUS po NIP-ie sklepu. Nazwa sklepu
     * w aplikacji to nazwa wewnętrzna (np. Zielona Góra - Kisielińska), więc nigdy nie jest nazwą pracodawcy.
     */
    TimeSettingsResponse.Employer employerOf(UUID storeId) {
        return employer(store(storeId));
    }

    private TimeSettingsResponse.Employer employer(Store store) {
        return employers.findActive(store.companyKey())
                .map(saved -> new TimeSettingsResponse.Employer(saved.getName(), saved.getAddress(), saved.getNip(),
                        saved.getRegon(), saved.getRepresentative(), true, false))
                .orElseGet(() -> gus.ofStore(store)
                        .map(company -> new TimeSettingsResponse.Employer(company.name(), company.address(),
                                company.nip(), company.regon(), null, false, true))
                        .orElseGet(() -> new TimeSettingsResponse.Employer(null, store.getAddress(), store.getNip(),
                                null, null, false, false)));
    }

    // ---------------------------------------------------------------- pomocnicze

    private int tolerance(UUID storeId, UUID departmentId) {
        return timeSettings.findActive(storeId, departmentId).map(setting -> setting.getToleranceMinutes()).orElse(0);
    }

    private StaffEmployee employeeOf(Store store, StaffDepartment department, UUID employeeId) {
        return employees.findWithDetails(employeeId)
                .filter(employee -> employee.getStore().getId().equals(store.getId())
                        && employee.getPosition().getDepartment().getId().equals(department.getId()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika w tym dziale"));
    }

    private StaffEmployee storeEmployee(UUID storeId, UUID employeeId) {
        return employees.findWithDetails(employeeId)
                .filter(employee -> employee.getStore().getId().equals(storeId) && employee.getEndedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
    }

    private static TimeCard.Interval shift(StaffScheduleEntry entry) {
        LocalDateTime start = entry.getWorkDate().atTime(entry.getStart());
        LocalDateTime end = (entry.getEnd().isAfter(entry.getStart()) ? entry.getWorkDate()
                : entry.getWorkDate().plusDays(1)).atTime(entry.getEnd());
        return new TimeCard.Interval(start, end);
    }

    private static StaffLeave leaveOn(List<StaffLeave> leavesOfEmployee, LocalDate day) {
        return leavesOfEmployee.stream()
                .filter(leave -> !day.isBefore(leave.getStartDate()) && !day.isAfter(leave.getEndDate()))
                .findFirst().orElse(null);
    }

    /** Jak w grafiku: urlop wypoczynkowy i na żądanie widzi każdy, resztę tylko osoba z dostępem do Kadr. */
    private static String absence(StaffLeave leave, boolean records) {
        LeaveKind kind = leave.getKind();
        return records || kind == LeaveKind.ANNUAL || kind == LeaveKind.ON_DEMAND ? kind.name() : "OTHER";
    }

    private static boolean overlaps(StaffContract contract, LocalDate from, LocalDate to) {
        return !contract.getStartDate().isAfter(to)
                && (contract.getEndDate() == null || !contract.getEndDate().isBefore(from));
    }

    private void requireDecide(StaffDepartment department, AppUserPrincipal principal) {
        if (!canDecide(department, principal)) {
            throw ApiException.forbidden("O czasie pracy działu decyduje jego kierownik");
        }
    }

    private boolean canDecide(StaffDepartment department, AppUserPrincipal principal) {
        return has(principal, TIME_EDIT) && (canDecideAll(principal)
                || managers.findDepartmentIds(principal.getId()).contains(department.getId()));
    }

    private static boolean canDecideAll(AppUserPrincipal principal) {
        return has(principal, AppUserPrincipal.OWNER_AUTHORITY) || has(principal, STAFF_EDIT);
    }

    private static boolean canConfigure(AppUserPrincipal principal) {
        return has(principal, AppUserPrincipal.OWNER_AUTHORITY) || has(principal, STAFF_EDIT);
    }

    private static boolean has(AppUserPrincipal principal, String authority) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private StaffDepartment department(Store store, UUID departmentId) {
        return departments.findInCompany(store.companyKey(), departmentId)
                .filter(department -> department.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, StoreTime.ZONE).truncatedTo(ChronoUnit.MINUTES);
    }

    private static Instant instant(LocalDateTime local) {
        return local.atZone(StoreTime.ZONE).toInstant();
    }

    /** Numer karty jak z czytnika: wielkie litery szesnastkowe bez dwukropków i spacji. */
    static String normalizeUid(String uid) {
        String normalized = uid.replaceAll("[\\s:\\-]", "").toUpperCase();
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("Podaj numer karty");
        }
        return normalized;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
