package com.intermanage.backend.staff.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.NetPay;
import com.intermanage.backend.staff.component.PeriodBalance;
import com.intermanage.backend.staff.component.ScheduleCalendar;
import com.intermanage.backend.staff.component.ScheduleCalendar.Period;
import com.intermanage.backend.staff.component.ScheduleRules;
import com.intermanage.backend.staff.component.ScheduleRules.Shift;
import com.intermanage.backend.staff.component.StaffFile;
import com.intermanage.backend.staff.dto.ScheduleChangeRequest;
import com.intermanage.backend.staff.dto.ScheduleDepartment;
import com.intermanage.backend.staff.dto.ScheduleLayoutRequest;
import com.intermanage.backend.staff.dto.ScheduleOpenHolidayRequest;
import com.intermanage.backend.staff.dto.SchedulePublishRequest;
import com.intermanage.backend.staff.dto.ScheduleResponse;
import com.intermanage.backend.staff.dto.ScheduleSettings;
import com.intermanage.backend.staff.dto.ScheduleSettingsRequest;
import com.intermanage.backend.staff.dto.ScheduleTimeOffRequest;
import com.intermanage.backend.staff.dto.ScheduleRateRequest;
import com.intermanage.backend.staff.dto.ShiftResponse;
import com.intermanage.backend.staff.dto.StaffRecordResponse;
import com.intermanage.backend.staff.dto.TimeBalanceResponse;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.ScheduleDayOffKind;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffContractChange;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.entity.StaffDepartmentBreak;
import com.intermanage.backend.staff.entity.StaffDepartmentTimeSetting;
import com.intermanage.backend.staff.entity.StaffDepartmentManager;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffOpenHoliday;
import com.intermanage.backend.staff.entity.StaffPosition;
import com.intermanage.backend.staff.entity.StaffPositionMinimum;
import com.intermanage.backend.staff.entity.StaffSchedule;
import com.intermanage.backend.staff.entity.StaffScheduleDayOff;
import com.intermanage.backend.staff.entity.StaffScheduleEntry;
import com.intermanage.backend.staff.entity.StaffScheduleGroup;
import com.intermanage.backend.staff.entity.StaffScheduleMember;
import com.intermanage.backend.staff.entity.StaffScheduleVersion;
import com.intermanage.backend.staff.entity.StaffScheduleVersionDayOff;
import com.intermanage.backend.staff.entity.StaffScheduleVersionEntry;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.StaffOvertimeTimeOff;
import com.intermanage.backend.staff.entity.StaffDepartmentPaySetting;
import com.intermanage.backend.staff.entity.StaffFlexibleTimeSetting;
import com.intermanage.backend.staff.entity.StaffHourlyRate;
import com.intermanage.backend.staff.entity.TimeOffInitiative;
import com.intermanage.backend.staff.repository.StaffContractChangeRepository;
import com.intermanage.backend.staff.repository.StaffContractRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentBreakRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentTimeSettingRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentManagerRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentRepository;
import com.intermanage.backend.staff.repository.StaffDisabilityRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffOpenHolidayRepository;
import com.intermanage.backend.staff.repository.StaffDepartmentPaySettingRepository;
import com.intermanage.backend.staff.repository.StaffEmployeePositionRepository;
import com.intermanage.backend.staff.repository.StaffFlexibleTimeSettingRepository;
import com.intermanage.backend.staff.repository.StaffHourlyRateRepository;
import com.intermanage.backend.staff.repository.StaffOvertimeTimeOffRepository;
import com.intermanage.backend.staff.repository.StaffPositionMinimumRepository;
import com.intermanage.backend.staff.repository.StaffPositionRepository;
import com.intermanage.backend.staff.repository.StaffScheduleDayOffRepository;
import com.intermanage.backend.staff.repository.StaffScheduleEntryRepository;
import com.intermanage.backend.staff.repository.StaffScheduleGroupRepository;
import com.intermanage.backend.staff.repository.StaffScheduleMemberRepository;
import com.intermanage.backend.staff.repository.StaffScheduleRepository;
import com.intermanage.backend.staff.repository.StaffScheduleVersionDayOffRepository;
import com.intermanage.backend.staff.repository.StaffScheduleVersionEntryRepository;
import com.intermanage.backend.staff.repository.StaffScheduleVersionRepository;
import com.intermanage.backend.staff.repository.StaffShiftRepository;
import com.intermanage.backend.store.dto.StoreSummary;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Grafik działu na miesiąc. Wpis leży w grafiku sklepu, w którym pracownik tego dnia pracuje, a godziny, wymiar i
 * odpoczynek liczą się z wpisów ze wszystkich sklepów firmy. Reguły: {@link ScheduleRules}.
 */
@Service
public class StaffScheduleService {

    private static final String STAFF_EDIT = "STAFF:EDIT";
    private static final int LOOKBACK_DAYS = 28;
    private static final int NOTICE_DAYS = 7;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final StaffScheduleRepository schedules;
    private final StaffScheduleEntryRepository entries;
    private final StaffScheduleVersionRepository versions;
    private final StaffScheduleVersionEntryRepository versionEntries;
    private final StaffDepartmentManagerRepository managers;
    private final StaffPositionMinimumRepository minimums;
    private final StaffDepartmentBreakRepository breaks;
    private final StaffDepartmentTimeSettingRepository timeSettings;
    private final StaffOpenHolidayRepository openHolidays;
    private final StaffScheduleDayOffRepository daysOff;
    private final StaffScheduleGroupRepository scheduleGroups;
    private final StaffScheduleMemberRepository scheduleMembers;
    private final StaffScheduleVersionDayOffRepository versionDaysOff;
    private final StaffDepartmentRepository departments;
    private final StaffPositionRepository positions;
    private final StaffEmployeeRepository employees;
    private final StaffShiftRepository shifts;
    private final StaffContractRepository contracts;
    private final StaffContractChangeRepository contractChanges;
    private final StaffDisabilityRepository disabilities;
    private final StaffLeaveRepository leaves;
    private final StaffLeaveDays leaveDays;
    private final StaffEmployeeService employeeService;
    private final StoreRepository stores;
    private final AppUserRepository users;
    private final StaffTimeService timeService;
    private final StaffOvertimeTimeOffRepository timeOffs;
    private final StaffDepartmentPaySettingRepository paySettings;
    private final StaffFlexibleTimeSettingRepository flexibleTime;
    private final StaffEmployeePositionRepository employeePositions;
    private final StaffHourlyRateRepository hourlyRates;

    public StaffScheduleService(StaffScheduleRepository schedules, StaffScheduleEntryRepository entries,
            StaffScheduleVersionRepository versions, StaffScheduleVersionEntryRepository versionEntries,
            StaffDepartmentManagerRepository managers, StaffPositionMinimumRepository minimums,
            StaffDepartmentBreakRepository breaks, StaffDepartmentTimeSettingRepository timeSettings,
            StaffOpenHolidayRepository openHolidays, StaffScheduleDayOffRepository daysOff,
            StaffScheduleGroupRepository scheduleGroups, StaffScheduleMemberRepository scheduleMembers,
            StaffScheduleVersionDayOffRepository versionDaysOff, StaffDepartmentRepository departments, StaffPositionRepository positions, StaffEmployeeRepository employees,
            StaffShiftRepository shifts, StaffContractRepository contracts,
            StaffContractChangeRepository contractChanges, StaffDisabilityRepository disabilities,
            StaffLeaveRepository leaves, StaffEmployeeService employeeService, StoreRepository stores,
            AppUserRepository users, StaffLeaveDays leaveDays, StaffTimeService timeService,
            StaffOvertimeTimeOffRepository timeOffs, StaffDepartmentPaySettingRepository paySettings,
            StaffHourlyRateRepository hourlyRates, StaffFlexibleTimeSettingRepository flexibleTime,
            StaffEmployeePositionRepository employeePositions) {
        this.schedules = schedules;
        this.entries = entries;
        this.versions = versions;
        this.versionEntries = versionEntries;
        this.managers = managers;
        this.minimums = minimums;
        this.breaks = breaks;
        this.timeSettings = timeSettings;
        this.openHolidays = openHolidays;
        this.daysOff = daysOff;
        this.scheduleGroups = scheduleGroups;
        this.scheduleMembers = scheduleMembers;
        this.versionDaysOff = versionDaysOff;
        this.departments = departments;
        this.positions = positions;
        this.employees = employees;
        this.shifts = shifts;
        this.contracts = contracts;
        this.contractChanges = contractChanges;
        this.disabilities = disabilities;
        this.leaves = leaves;
        this.employeeService = employeeService;
        this.stores = stores;
        this.users = users;
        this.leaveDays = leaveDays;
        this.timeService = timeService;
        this.timeOffs = timeOffs;
        this.paySettings = paySettings;
        this.hourlyRates = hourlyRates;
        this.flexibleTime = flexibleTime;
        this.employeePositions = employeePositions;
    }

    /** Zmiana w grafiku albo w jego opublikowanej wersji, sprowadzona do tego, co potrzebne w tabeli. */
    private record Planned(UUID employeeId, LocalDate day, LocalTime start, LocalTime end, Store store, UUID scheduleId,
            boolean civil, UUID positionId) {

        Planned(StaffScheduleEntry entry) {
            this(entry.getEmployee().getId(), entry.getWorkDate(), entry.getStart(), entry.getEnd(),
                    entry.getSchedule().getStore(), entry.getSchedule().getId(), entry.isCivil(), entry.getPositionId());
        }

        Planned(StaffScheduleVersionEntry entry) {
            this(entry.getEmployee().getId(), entry.getWorkDate(), entry.getStart(), entry.getEnd(),
                    entry.getVersion().getSchedule().getStore(), entry.getVersion().getSchedule().getId(),
                    entry.isCivil(), entry.getPositionId());
        }

        Shift shift() {
            return new Shift(day, start, end);
        }
    }

    /** Dzień wolny w grafiku albo w jego opublikowanej wersji. */
    private record PlannedOff(UUID employeeId, LocalDate day, ScheduleDayOffKind kind, LocalDate forDate, UUID scheduleId) {

        PlannedOff(StaffScheduleDayOff off) {
            this(off.getEmployee().getId(), off.getDay(), off.getKind(), off.getForDate(), off.getSchedule().getId());
        }

        PlannedOff(StaffScheduleVersionDayOff off) {
            this(off.getEmployee().getId(), off.getDay(), off.getKind(), off.getForDate(),
                    off.getVersion().getSchedule().getId());
        }
    }

    @Transactional(readOnly = true)
    public List<ScheduleDepartment> departments(UUID storeId, AppUserPrincipal principal) {
        Set<UUID> managed = new HashSet<>(managers.findDepartmentIds(principal.getId()));
        boolean all = canEditAll(principal);
        boolean edits = has(principal, "STAFF_SCHEDULE:EDIT");
        return departments.findActive(store(storeId).companyKey()).stream()
                .map(department -> new ScheduleDepartment(department.getId(), department.getName(),
                        edits && (all || managed.contains(department.getId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(UUID storeId, UUID departmentId, String month, Integer version,
            AppUserPrincipal principal) {
        return view(store(storeId), department(storeId, departmentId), month(month), version, principal);
    }

    @Transactional
    public ScheduleResponse change(UUID storeId, ScheduleChangeRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        requireEdit(department, principal);
        Map<UUID, Store> allowed = allowedStores(store, principal.getId());
        AppUser actor = users.getReferenceById(principal.getId());

        List<ScheduleChangeRequest.Change> wanted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<UUID, Set<UUID>> skills = dayPositions(request.changes().stream()
                .map(ScheduleChangeRequest.Change::employeeId).collect(Collectors.toSet()), department);
        for (ScheduleChangeRequest.Change requested : request.changes()) {
            ScheduleChangeRequest.Change change = requested;
            if (!seen.add(change.employeeId() + "/" + change.date() + "/" + change.isCivil())) {
                throw ApiException.badRequest("Ten sam dzień pracownika podano dwa razy");
            }
            StaffEmployee employee = employees.findWithDetails(change.employeeId())
                    .filter(found -> found.getStore().companyKey().equals(store.companyKey()))
                    .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
            if (change.dayOff() != null && change.start() != null) {
                throw ApiException.badRequest("Dzień jest albo zmianą, albo dniem wolnym");
            }
            change = change.withPosition(dayPosition(employee, change, skills));
            if (change.isCivil()) {
                // Wiersz zlecenia ma tylko godziny: dni wolne dotyczą umowy o pracę.
                if (change.dayOff() != null) {
                    throw ApiException.badRequest("Dni wolne oznacza się w wierszu umowy o pracę");
                }
                StaffScheduleEntry civil = currentCivil(employee.getId(), change.date());
                if (civil != null) {
                    requireOwned(civil.getSchedule(), change.date(), department, allowed, employee);
                    if (same(civil, change, store)) {
                        continue;
                    }
                    civil.markDeleted(actor);
                }
                if (change.start() != null) {
                    wanted.add(change);
                }
                continue;
            }
            // Dzień ma jedno z dwóch: wpisanie zmiany zdejmuje dzień wolny i odwrotnie.
            StaffScheduleEntry current = current(employee.getId(), change.date());
            StaffScheduleDayOff off = currentOff(employee.getId(), change.date());
            if (current != null) {
                requireOwned(current.getSchedule(), change.date(), department, allowed, employee);
                if (same(current, change, store)) {
                    continue;
                }
                current.markDeleted(actor);
            }
            if (off != null) {
                requireOwned(off.getSchedule(), change.date(), department, allowed, employee);
                if (off.getKind() == change.dayOff() && Objects.equals(off.getForDate(), change.forDate())) {
                    continue;
                }
                off.markDeleted(actor);
            }
            if (change.start() != null || change.dayOff() != null) {
                wanted.add(change);
            }
        }
        // Nowy wpis ma ten sam klucz co usunięty, a Hibernate wstawia przed aktualizacją.
        entries.flush();
        daysOff.flush();
        for (ScheduleChangeRequest.Change change : wanted) {
            if (change.dayOff() != null) {
                daysOff.saveAndFlush(dayOff(change, store, department, actor));
            } else {
                entries.save(entry(change, store, department, allowed, actor));
            }
        }
        entries.flush();
        // Urlop zużywa dni pracy z grafiku, więc zmiana grafiku pod urlopem zmienia liczbę jego dni.
        request.changes().stream().map(ScheduleChangeRequest.Change::date).min(Comparator.naturalOrder())
                .ifPresent(from -> leaveDays.recount(
                        request.changes().stream().map(ScheduleChangeRequest.Change::employeeId)
                                .collect(Collectors.toSet()),
                        from, request.changes().stream().map(ScheduleChangeRequest.Change::date)
                                .max(Comparator.naturalOrder()).orElse(from)));
        return view(store, department, month(request.month()), null, principal);
    }

    @Transactional
    public ScheduleResponse publish(UUID storeId, SchedulePublishRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        requireEdit(department, principal);
        YearMonth month = month(request.month());
        AppUser actor = users.getReferenceById(principal.getId());
        StaffSchedule schedule = schedule(store, department, month.atDay(1), actor);
        List<StaffScheduleVersion> published = versions.findOfSchedule(schedule.getId());
        List<StaffScheduleEntry> active = entries.findActive(schedule.getId());
        List<StaffScheduleDayOff> activeOff = daysOff.findActive(schedule.getId());
        if (!published.isEmpty() && !changedSince(published.getLast(), active, activeOff)) {
            throw ApiException.conflict("Grafik nie zmienił się od ostatniej publikacji");
        }
        StaffScheduleVersion version = versions.saveAndFlush(
                new StaffScheduleVersion(schedule, published.size() + 1, actor));
        versionEntries.saveAll(active.stream().map(entry -> new StaffScheduleVersionEntry(version, entry)).toList());
        versionEntries.flush();
        versionDaysOff.saveAll(activeOff.stream().map(off -> new StaffScheduleVersionDayOff(version, off)).toList());
        versionDaysOff.flush();
        return view(store, department, month, null, principal);
    }

    /** Układ tylko dla pracowników działu w tym sklepie: goście stoją zawsze w swojej grupie na końcu. */
    @Transactional
    public ScheduleResponse saveLayout(UUID storeId, ScheduleLayoutRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        requireEdit(department, principal);
        AppUser actor = users.getReferenceById(principal.getId());
        Map<UUID, StaffEmployee> team = team(store, department, Set.of()).stream()
                .collect(Collectors.toMap(StaffEmployee::getId, Function.identity()));
        Map<UUID, StaffScheduleMember> members = scheduleMembers.find(storeId, department.getId()).stream()
                .collect(Collectors.toMap(member -> member.getEmployee().getId(), Function.identity()));
        Map<UUID, StaffScheduleGroup> current = scheduleGroups.findActive(storeId, department.getId()).stream()
                .collect(Collectors.toMap(StaffScheduleGroup::getId, Function.identity()));

        Set<UUID> placed = new HashSet<>();
        int[] position = {0};
        BiConsumer<List<UUID>, StaffScheduleGroup> place = (ids, group) -> {
            for (UUID id : ids) {
                StaffEmployee employee = team.get(id);
                if (employee == null || !placed.add(id)) {
                    throw ApiException.badRequest("Układ ma pracownika spoza działu albo tego samego dwa razy");
                }
                StaffScheduleMember member = members.get(id);
                if (member == null) {
                    member = scheduleMembers.save(new StaffScheduleMember(store, department, employee));
                }
                member.place(group, position[0]++);
            }
        };
        place.accept(request.ungrouped(), null);
        int groupPosition = 0;
        Set<UUID> kept = new HashSet<>();
        for (ScheduleLayoutRequest.Group requested : request.groups()) {
            StaffScheduleGroup group = requested.id() == null ? new StaffScheduleGroup(store, department, actor)
                    : current.get(requested.id());
            if (group == null) {
                throw ApiException.badRequest("Wybrana grupa nie istnieje");
            }
            group.update(requested.name().trim(), groupPosition++);
            scheduleGroups.saveAndFlush(group);
            kept.add(group.getId());
            place.accept(requested.employeeIds(), group);
        }
        current.values().stream().filter(group -> !kept.contains(group.getId()))
                .forEach(group -> group.markDeleted(actor));
        scheduleMembers.flush();
        scheduleGroups.flush();
        return view(store, department, month(request.month()), null, principal);
    }

    /**
     * Czas wolny za nadgodziny pracownika danego dnia (art. 151² KP): zastępuje poprzedni wpis tego dnia (znacznik),
     * puste minuty go usuwają. Dzień może mieć też krótszą zmianę, ale nie dzień wolny ani nieobecność.
     */
    @Transactional
    public ScheduleResponse timeOff(UUID storeId, ScheduleTimeOffRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        requireEdit(department, principal);
        StaffEmployee employee = employees.findWithDetails(request.employeeId())
                .filter(found -> found.getStore().companyKey().equals(store.companyKey()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
        LocalDate day = request.date();
        AppUser actor = users.getReferenceById(principal.getId());
        StaffOvertimeTimeOff current = timeOffs.findActive(employee.getId(), day).orElse(null);
        if (request.minutes() != null) {
            if (request.initiative() == null) {
                throw ApiException.badRequest("Wskaż, czyja to inicjatywa: pracownika czy pracodawcy");
            }
            if (current != null && current.getMinutes() == request.minutes()
                    && current.getInitiative() == request.initiative()) {
                return view(store, department, month(request.month()), null, principal);
            }
            if (currentOff(employee.getId(), day) != null) {
                throw ApiException.conflict("Ten dzień jest już dniem wolnym (W, W5 albo WN)");
            }
            if (leaves.findActive(List.of(employee.getId())).stream()
                    .anyMatch(leave -> !day.isBefore(leave.getStartDate()) && !day.isAfter(leave.getEndDate()))) {
                throw ApiException.conflict("W dzień nieobecności nie udziela się czasu wolnego za nadgodziny");
            }
        }
        if (current != null) {
            current.markDeleted(actor);
            timeOffs.flush();
        }
        if (request.minutes() != null) {
            timeOffs.saveAndFlush(new StaffOvertimeTimeOff(employee, store, day, request.minutes(),
                    request.initiative(), actor));
        }
        return view(store, department, month(request.month()), null, principal);
    }

    /** Saldo okresu jednego pracownika firmy do okna decyzji o nadgodzinach w Czasie pracy, liczone jak w grafiku. */
    @Transactional(readOnly = true)
    public TimeBalanceResponse balance(UUID storeId, UUID employeeId, LocalDate date) {
        Store store = store(storeId);
        StaffEmployee employee = employees.findWithDetails(employeeId)
                .filter(found -> found.getStore().companyKey().equals(store.companyKey()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
        Period period = ScheduleCalendar.period(date);
        List<UUID> ids = List.of(employeeId);
        StaffFile file = files(ids).get(employeeId);
        List<Planned> planned = entries.findOfEmployees(ids, period.start(), period.end()).stream()
                .map(Planned::new).toList();
        Map<LocalDate, Integer> actual = timeService.countedMinutes(List.of(employee), period.start(), period.end())
                .getOrDefault(employeeId, Map.of());
        Map<LocalDate, StaffOvertimeTimeOff> free = timeOffs.findOfEmployees(ids, period.start(), period.end()).stream()
                .collect(Collectors.toMap(StaffOvertimeTimeOff::getWorkDate, Function.identity(), (a, b) -> a));
        boolean anyEmployment = period.days().stream().anyMatch(day -> employment(file, day) != null);
        PeriodBalance.Sum sum = hours(period.days(), planned, leaves.findActive(ids), file, actual, free);
        return new TimeBalanceResponse(period.start(), period.end(), sum.worked(), anyEmployment ? sum.norm() : null,
                sum.dailyOvertime(), sum.toCompensate(),
                actual.keySet().stream().max(Comparator.naturalOrder()).orElse(null), dailyLimit(file, date));
    }

    /**
     * Stawka za godzinę pracownika od miesiąca grafiku (umowa o pracę albo zlecenie). Zmienia ją tylko osoba, która
     * układa grafik działu z włączonym liczeniem wynagrodzeń; pusta stawka usuwa ją od tego miesiąca (znacznik).
     */
    @Transactional
    public ScheduleResponse rate(UUID storeId, ScheduleRateRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        requireEdit(department, principal);
        if (paySettings.findActive(storeId, department.getId()).isEmpty()) {
            throw ApiException.conflict("Ten dział nie liczy wynagrodzeń w grafiku. Włącz to w ustawieniach działu.");
        }
        StaffEmployee employee = employees.findWithDetails(request.employeeId())
                .filter(found -> found.getStore().companyKey().equals(store.companyKey()))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono pracownika"));
        LocalDate from = month(request.month()).atDay(1);
        AppUser actor = users.getReferenceById(principal.getId());
        hourlyRates.findUpTo(List.of(employee.getId()), from).stream()
                .filter(rate -> rate.isCivil() == request.civil() && rate.getValidFrom().equals(from))
                .forEach(rate -> rate.markDeleted(actor));
        hourlyRates.flush();
        if (request.rate() != null) {
            hourlyRates.saveAndFlush(new StaffHourlyRate(employee, request.civil(), from, request.rate(), actor));
        }
        return view(store, department, month(request.month()), null, principal);
    }

    /** Sklep czynny w święto to decyzja o całym sklepie, więc nie kierownika działu. */
    @Transactional
    public ScheduleResponse openHoliday(UUID storeId, ScheduleOpenHolidayRequest request, AppUserPrincipal principal) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, request.departmentId());
        LocalDate day = request.date();
        if (!ScheduleCalendar.isClosed(day)) {
            throw ApiException.badRequest("Ten dzień nie jest świętem");
        }
        AppUser actor = users.getReferenceById(principal.getId());
        List<StaffOpenHoliday> current = openHolidays.findActive(List.of(storeId), day, day);
        if (request.open() && current.isEmpty()) {
            openHolidays.saveAndFlush(new StaffOpenHoliday(store, day, actor));
        }
        if (!request.open()) {
            current.forEach(holiday -> holiday.markDeleted(actor));
            openHolidays.flush();
        }
        return view(store, department, month(request.month()), null, principal);
    }

    @Transactional(readOnly = true)
    public ScheduleSettings settings(UUID storeId, UUID departmentId) {
        Store store = store(storeId);
        department(storeId, departmentId);
        List<UUID> managerIds = managers.findActive(departmentId).stream().map(m -> m.getUser().getId()).toList();
        List<UUID> companyStores = store.getNip() == null ? List.of(storeId)
                : stores.findActiveByNip(store.getNip()).stream().map(Store::getId).toList();
        List<ScheduleSettings.Person> people = employees.findInStores(companyStores).stream()
                .filter(employee -> employee.getEndedAt() == null && employee.hasAccount())
                .map(employee -> new ScheduleSettings.Person(employee.getUser().getId(),
                        employee.getDisplayName(), employee.getPosition().getName()))
                .toList();
        Map<UUID, StaffPositionMinimum> counts = staffing(storeId, departmentId);
        List<ScheduleSettings.Minimum> result = activePositions(store, departmentId).stream()
                .map(position -> {
                    StaffPositionMinimum count = counts.get(position.getId());
                    return new ScheduleSettings.Minimum(position.getId(), position.getName(),
                            count == null ? null : count.getMinCount(), count == null ? null : count.getTargetCount());
                })
                .toList();
        List<ScheduleSettings.Break> breakList = breaks.findActive(storeId, departmentId).stream()
                .map(item -> new ScheduleSettings.Break(item.getStart().toString(), item.getEnd().toString(),
                        item.isPaid()))
                .toList();
        int tolerance = timeSettings.findActive(storeId, departmentId)
                .map(StaffDepartmentTimeSetting::getToleranceMinutes).orElse(0);
        return new ScheduleSettings(managerIds, people, result, breakList, tolerance,
                paySettings.findActive(storeId, departmentId).isPresent(),
                flexibleTime.findActive(storeId).isPresent());
    }

    @Transactional
    public ScheduleSettings saveSettings(UUID storeId, UUID departmentId, ScheduleSettingsRequest request,
            UUID actorId) {
        Store store = store(storeId);
        StaffDepartment department = department(storeId, departmentId);
        AppUser actor = users.getReferenceById(actorId);

        Set<UUID> wanted = new LinkedHashSet<>(request.managerIds());
        Set<UUID> eligible = settings(storeId, departmentId).people().stream().map(ScheduleSettings.Person::userId)
                .collect(Collectors.toSet());
        for (StaffDepartmentManager manager : managers.findActive(departmentId)) {
            if (!wanted.remove(manager.getUser().getId())) {
                manager.markDeleted(actor);
            }
        }
        for (UUID userId : wanted) {
            if (!eligible.contains(userId)) {
                throw ApiException.badRequest("Grafik układa pracownik firmy");
            }
            managers.save(new StaffDepartmentManager(department, users.getReferenceById(userId), actor));
        }

        Map<UUID, StaffPosition> known = activePositions(store, departmentId).stream()
                .collect(Collectors.toMap(StaffPosition::getId, Function.identity()));
        Map<UUID, StaffPositionMinimum> current = staffing(storeId, departmentId);
        List<StaffPositionMinimum> added = new ArrayList<>();
        for (ScheduleSettingsRequest.Minimum minimum : request.minimums()) {
            StaffPosition position = known.get(minimum.positionId());
            if (position == null) {
                throw ApiException.badRequest("Wybrane stanowisko nie należy do działu");
            }
            if (minimum.minCount() != null && minimum.targetCount() != null
                    && minimum.targetCount() < minimum.minCount()) {
                throw ApiException.badRequest("Docelowa obsada stanowiska " + position.getName()
                        + " nie może być mniejsza niż minimum");
            }
            StaffPositionMinimum existing = current.get(position.getId());
            if (existing == null ? minimum.minCount() == null && minimum.targetCount() == null
                    : Objects.equals(existing.getMinCount(), minimum.minCount())
                            && Objects.equals(existing.getTargetCount(), minimum.targetCount())) {
                continue;
            }
            if (existing != null) {
                existing.markDeleted(actor);
            }
            if (minimum.minCount() != null || minimum.targetCount() != null) {
                added.add(new StaffPositionMinimum(position, store, minimum.minCount(), minimum.targetCount(), actor));
            }
        }
        minimums.flush();
        minimums.saveAll(added);
        minimums.flush();
        if (request.breaks() != null) {
            saveBreaks(store, department, request.breaks(), actor);
        }
        if (request.toleranceMinutes() != null) {
            StaffDepartmentTimeSetting setting = timeSettings.findActive(storeId, departmentId).orElse(null);
            if (setting == null ? request.toleranceMinutes() != 0
                    : setting.getToleranceMinutes() != request.toleranceMinutes()) {
                if (setting != null) {
                    setting.markDeleted(actor);
                    timeSettings.flush();
                }
                timeSettings.saveAndFlush(new StaffDepartmentTimeSetting(department, store,
                        request.toleranceMinutes(), actor));
            }
        }
        if (request.payEnabled() != null) {
            StaffDepartmentPaySetting pay = paySettings.findActive(storeId, departmentId).orElse(null);
            if (request.payEnabled() && pay == null) {
                paySettings.saveAndFlush(new StaffDepartmentPaySetting(department, store, actor));
            }
            if (!request.payEnabled() && pay != null) {
                pay.markDeleted(actor);
                paySettings.flush();
            }
        }
        if (request.flexibleTime() != null) {
            StaffFlexibleTimeSetting flexible = flexibleTime.findActive(storeId).orElse(null);
            if (request.flexibleTime() && flexible == null) {
                flexibleTime.saveAndFlush(new StaffFlexibleTimeSetting(store, actor));
            }
            if (!request.flexibleTime() && flexible != null) {
                flexible.markDeleted(actor);
                flexibleTime.flush();
            }
        }
        return settings(storeId, departmentId);
    }

    /** Zastępuje przerwy działu w sklepie; niezmienione zostają, zmienione i usunięte dostają znacznik. */
    private void saveBreaks(Store store, StaffDepartment department, List<ScheduleSettingsRequest.Break> wanted,
            AppUser actor) {
        record Hours(LocalTime start, LocalTime end, boolean paid) {
        }
        List<Hours> requested = wanted.stream()
                .map(item -> new Hours(LocalTime.parse(item.start()), LocalTime.parse(item.end()), item.paid()))
                .sorted(Comparator.comparing(Hours::start))
                .toList();
        for (int i = 0; i < requested.size(); i++) {
            Hours item = requested.get(i);
            if (!item.end().isAfter(item.start())) {
                throw ApiException.badRequest("Przerwa " + item.start() + "-" + item.end()
                        + " musi kończyć się po początku");
            }
            if (i > 0 && requested.get(i - 1).end().isAfter(item.start())) {
                throw ApiException.badRequest("Przerwy nie mogą na siebie nachodzić");
            }
        }
        List<Hours> missing = new ArrayList<>(requested);
        for (StaffDepartmentBreak current : breaks.findActive(store.getId(), department.getId())) {
            if (!missing.remove(new Hours(current.getStart(), current.getEnd(), current.isPaid()))) {
                current.markDeleted(actor);
            }
        }
        breaks.flush();
        breaks.saveAll(missing.stream()
                .map(item -> new StaffDepartmentBreak(department, store, item.start(), item.end(), item.paid(), actor))
                .toList());
    }

    private ScheduleResponse view(Store store, StaffDepartment department, YearMonth month, Integer versionNumber,
            AppUserPrincipal principal) {
        boolean canEdit = canEdit(department, principal);
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        Period period = ScheduleCalendar.period(first);
        StaffSchedule schedule = schedules.find(store.getId(), department.getId(), first).orElse(null);
        List<StaffScheduleVersion> published = schedule == null ? List.of() : versions.findOfSchedule(schedule.getId());

        StaffScheduleVersion viewed = versionNumber == null ? null : published.stream()
                .filter(version -> version.getNumber() == versionNumber).findFirst()
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono wersji grafiku"));
        boolean live = canEdit && viewed == null;
        boolean records = seesRecords(principal);
        List<StaffScheduleEntry> liveEntries = schedule == null ? List.of() : entries.findActive(schedule.getId());

        // Bez prawa układania widać tylko to, co opublikowano, więc lista wierszy też pochodzi z publikacji.
        StaffScheduleVersion shown = viewed != null ? viewed : published.isEmpty() ? null : published.getLast();
        List<Planned> ownAll = live ? liveEntries.stream().map(Planned::new).toList()
                : shown == null ? List.of()
                        : versionEntries.findEntries(shown.getId()).stream().map(Planned::new).toList();
        // Godziny zlecenia mają osobny wiersz pod umową o pracę i nie wchodzą do jej sum ani reguł.
        List<Planned> own = ownAll.stream().filter(planned -> !planned.civil()).toList();
        Map<UUID, List<Planned>> civilByEmployee = ownAll.stream().filter(Planned::civil)
                .collect(Collectors.groupingBy(Planned::employeeId));

        List<StaffScheduleDayOff> liveOff = schedule == null ? List.of() : daysOff.findActive(schedule.getId());
        List<PlannedOff> ownOff = live ? liveOff.stream().map(PlannedOff::new).toList()
                : shown == null ? List.of()
                        : versionDaysOff.findOfVersion(shown.getId()).stream().map(PlannedOff::new).toList();

        Set<UUID> rowIds = ownAll.stream().map(Planned::employeeId).collect(Collectors.toCollection(HashSet::new));
        ownOff.forEach(off -> rowIds.add(off.employeeId()));
        List<StaffEmployee> team = team(store, department, rowIds);
        List<StaffScheduleGroup> layoutGroups = scheduleGroups.findActive(store.getId(), department.getId());
        Set<UUID> activeGroups = layoutGroups.stream().map(StaffScheduleGroup::getId).collect(Collectors.toSet());
        Map<UUID, UUID> groupOf = new HashMap<>();
        scheduleMembers.find(store.getId(), department.getId()).stream()
                .filter(member -> member.getGroup() != null && activeGroups.contains(member.getGroup().getId()))
                .forEach(member -> groupOf.put(member.getEmployee().getId(), member.getGroup().getId()));
        List<UUID> ids = team.stream().map(StaffEmployee::getId).toList();
        List<StaffPosition> departmentPositions = activePositions(store, department.getId());

        Map<UUID, List<Planned>> elsewhere = new HashMap<>();
        UUID scheduleId = schedule == null ? null : schedule.getId();
        if (!ids.isEmpty()) {
            List<Planned> all = live
                    ? entries.findOfEmployees(ids, first, last).stream().map(Planned::new).toList()
                    : versionEntries.findLatestPublished(ids, first, last).stream().map(Planned::new).toList();
            all.stream().filter(planned -> !planned.scheduleId().equals(scheduleId))
                    .forEach(planned -> elsewhere.computeIfAbsent(planned.employeeId(), id -> new ArrayList<>())
                            .add(planned));
        }
        Map<UUID, List<Planned>> ownByEmployee = own.stream().collect(Collectors.groupingBy(Planned::employeeId));
        Map<UUID, List<PlannedOff>> offByEmployee = new HashMap<>();
        if (!ids.isEmpty()) {
            List<PlannedOff> all = live
                    ? daysOff.findOfEmployees(ids, first, last).stream().map(PlannedOff::new).toList()
                    : versionDaysOff.findLatestPublished(ids, first, last).stream().map(PlannedOff::new).toList();
            // Stara wersja pokazuje własne dni wolne z tamtej chwili, a z innych grafików ostatnią publikację.
            Stream.concat(ownOff.stream(), all.stream().filter(off -> !off.scheduleId().equals(scheduleId)))
                    .forEach(off -> offByEmployee.computeIfAbsent(off.employeeId(), id -> new ArrayList<>()).add(off));
        }

        Map<UUID, List<StaffLeave>> leavesByEmployee = ids.isEmpty() ? Map.of()
                : leaves.findActive(ids).stream().collect(Collectors.groupingBy(l -> l.getEmployee().getId()));
        Map<UUID, StaffFile> files = live ? files(ids) : Map.of();
        Set<OpenDay> open = openDays(store, period.start().minusDays(LOOKBACK_DAYS), period.end());
        Map<UUID, List<Planned>> history = live && !ids.isEmpty()
                ? entries.findOfEmployees(ids, period.start().minusDays(LOOKBACK_DAYS), period.end()).stream()
                        .map(Planned::new).collect(Collectors.groupingBy(Planned::employeeId))
                : Map.of();
        // WN może wypaść po końcu okresu: za niedzielę w ciągu 6 dni po niej.
        Map<UUID, List<PlannedOff>> offHistory = live && !ids.isEmpty()
                ? daysOff.findOfEmployees(ids, period.start().minusDays(LOOKBACK_DAYS),
                        period.end().plusDays(ScheduleRules.SUNDAY_DAY_OFF_DAYS)).stream()
                        .map(PlannedOff::new).collect(Collectors.groupingBy(PlannedOff::employeeId))
                : Map.of();
        // Za dni, które minęły, saldo okresu bierze czas z kart czasu pracy, a nie z planu.
        Map<UUID, Map<LocalDate, Integer>> actual = live
                ? timeService.countedMinutes(team, period.start(), period.end())
                : Map.of();
        // Czas wolny za nadgodziny jest poza wersjami grafiku, więc widzi go tylko układający.
        Map<UUID, Map<LocalDate, StaffOvertimeTimeOff>> timeOffOf = new HashMap<>();
        if (live && !ids.isEmpty()) {
            timeOffs.findOfEmployees(ids, period.start(), period.end()).forEach(item -> timeOffOf
                    .computeIfAbsent(item.getEmployee().getId(), key -> new HashMap<>())
                    .putIfAbsent(item.getWorkDate(), item));
        }
        // Zlecenie razem z umową o pracę łamie odpoczynek dobowy tak samo, więc reguły widzą też jego godziny.
        Map<UUID, List<Planned>> civilHistory = live && !ids.isEmpty()
                ? entries.findCivilOfEmployees(ids, period.start().minusDays(LOOKBACK_DAYS), period.end()).stream()
                        .map(Planned::new).collect(Collectors.groupingBy(Planned::employeeId))
                : Map.of();
        // Wynagrodzenia widzi tylko układający grafik działu, który je liczy.
        boolean showsPay = live && paySettings.findActive(store.getId(), department.getId()).isPresent();
        Map<String, BigDecimal> rates = new HashMap<>();
        if (showsPay && !ids.isEmpty()) {
            hourlyRates.findUpTo(ids, first)
                    .forEach(rate -> rates.put(rate.getEmployee().getId() + "/" + rate.isCivil(), rate.getRate()));
        }
        Map<UUID, List<StaffShift>> fixed = ids.isEmpty() || !live ? Map.of()
                : shifts.findActiveOfEmployees(ids).stream()
                        .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));

        List<LocalDate> days = first.datesUntil(last.plusDays(1)).toList();
        List<ScheduleResponse.Row> rows = new ArrayList<>();
        for (StaffEmployee employee : team) {
            UUID id = employee.getId();
            List<Planned> mine = ownByEmployee.getOrDefault(id, List.of());
            List<Planned> away = elsewhere.getOrDefault(id, List.of());
            List<StaffLeave> absent = leavesByEmployee.getOrDefault(id, List.of());
            Map<LocalDate, StaffOvertimeTimeOff> timeOffDays = timeOffOf.getOrDefault(id, Map.of());
            List<ScheduleResponse.Cell> cells = cells(days, mine, away, offByEmployee.getOrDefault(id, List.of()), absent,
                    store, records, timeOffDays);
            rows.add(live
                    ? editorRow(employee, groupOf.get(id), store, department, month, period, cells, absent, files.get(id),
                            history.getOrDefault(id, List.of()), offHistory.getOrDefault(id, List.of()),
                            fixed.getOrDefault(id, List.of()), open, actual.getOrDefault(id, Map.of()),
                            timeOffDays, civilHistory.getOrDefault(id, List.of()),
                            showsPay ? Optional.ofNullable(rates.get(id + "/false")) : null)
                    : new ScheduleResponse.Row(id, employee.getDisplayName(), employee.getPosition().getId(),
                            employee.getPosition().getName(), groupOf.get(id), employee.getStore().getNumber(),
                            guest(employee, store, department), null, null, null, null, null, List.of(), cells,
                            List.of(), List.of(), List.of(), null, null, null, null, null, null, false, null, null,
                            null, null));
            List<Planned> civilDays = civilByEmployee.getOrDefault(id, List.of());
            StaffFile file = files.get(id);
            boolean bothContracts = file != null && days.stream().anyMatch(day -> mandate(file, day))
                    && days.stream().anyMatch(day -> employment(file, day) != null);
            if (!civilDays.isEmpty() || bothContracts) {
                rows.add(civilRow(employee, groupOf.get(id), store, department, days, civilDays, file, records,
                        showsPay ? Optional.ofNullable(rates.get(id + "/true")) : null));
            }
        }

        String status = published.isEmpty() ? "DRAFT"
                : changedSince(published.getLast(), liveEntries, liveOff) ? "CHANGED" : "PUBLISHED";
        return new ScheduleResponse(department.getId(), month.toString(), status, canEdit && viewed == null,
                has(principal, STAFF_EDIT), viewed == null ? null : viewed.getNumber(), period.start(), period.end(),
                published.stream().map(version -> version(version, first)).toList(),
                days.stream().map(day -> day(day, open.contains(new OpenDay(store.getId(), day)))).toList(),
                positions(store, department, departmentPositions, days, rows),
                layoutGroups.stream().map(group -> new ScheduleResponse.Group(group.getId(), group.getName())).toList(),
                rows,
                employeeService.companyStores(store.getId(), principal.getId()), showsPay,
                dayPositions(rows.stream().map(ScheduleResponse.Row::employeeId).collect(Collectors.toSet()), department)
                        .entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))));
    }

    /**
     * Wiersz godzin na umowie zlecenia pod wierszem umowy o pracę. Bez wymiaru, salda i reguł: ostrzeżenia o łączeniu
     * obu umów są w wierszu umowy o pracę. Dni bez zlecenia w Kadrach są zakreskowane.
     */
    private ScheduleResponse.Row civilRow(StaffEmployee employee, UUID groupId, Store store, StaffDepartment department,
            List<LocalDate> days, List<Planned> civilDays, StaffFile file, boolean records, Optional<BigDecimal> rate) {
        List<ScheduleResponse.Cell> cells = cells(days, civilDays, List.of(), List.of(), List.of(), store, records,
                Map.of());
        int minutes = civilDays.stream().mapToInt(planned -> planned.shift().minutes()).sum();
        List<LocalDate> outOfContract = file == null ? List.of()
                : days.stream().filter(day -> !mandate(file, day)).toList();
        BigDecimal gross = rate == null ? null : rate.map(value -> money(minutes, value)).orElse(null);
        ScheduleResponse.Pay details = null;
        if (gross != null) {
            boolean employedHere = file != null && days.stream().anyMatch(day -> employment(file, day) != null);
            NetPay.Result net = NetPay.mandate(gross,
                    NetPay.Settings.of(file == null ? null : file.profile(), YearMonth.from(days.getFirst())),
                    employedHere);
            details = new ScheduleResponse.Pay(gross, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 0,
                    BigDecimal.ZERO.setScale(2), 0, net.social(), net.health(), net.tax(), net.net(), net.notes());
        }
        return new ScheduleResponse.Row(employee.getId(), employee.getDisplayName(),
                employee.getPosition().getId(), employee.getPosition().getName(), groupId,
                employee.getStore().getNumber(), guest(employee, store, department), "zlecenie", minutes, null, null,
                null, List.of(), cells, List.of(), List.of(), outOfContract, null, null, null, null, null, null, true,
                rate == null ? null : rate.orElse(null), gross, null, details);
    }

    private static boolean mandate(StaffFile file, LocalDate day) {
        return file.contracts().stream()
                .anyMatch(contract -> contract.getKind() == ContractKind.MANDATE && contract.activeOn(day));
    }

    private ScheduleResponse.Row editorRow(StaffEmployee employee, UUID groupId, Store store, StaffDepartment department,
            YearMonth month, Period period, List<ScheduleResponse.Cell> cells, List<StaffLeave> absent, StaffFile file,
            List<Planned> history, List<PlannedOff> offHistory, List<StaffShift> fixed, Set<OpenDay> open,
            Map<LocalDate, Integer> actual, Map<LocalDate, StaffOvertimeTimeOff> timeOff, List<Planned> civilShifts,
            Optional<BigDecimal> rate) {
        List<Planned> inPeriod = history.stream().filter(planned -> period.contains(planned.day())).toList();
        // Zlecenie i B2B nie podlegają czasowi pracy z KP; bez umowy w Kadrach reguły dobowe działają, wymiaru nie ma.
        List<StaffContract> covering = inPeriod.stream().map(planned -> file.currentContract(planned.day()))
                .filter(Objects::nonNull).toList();
        boolean civil = !covering.isEmpty() && covering.stream().noneMatch(StaffContract::isEmployment);
        boolean anyEmployment = period.days().stream().anyMatch(day -> employment(file, day) != null);

        PeriodBalance.Sum periodHours = hours(period.days(), inPeriod, absent, file, actual, timeOff);
        PeriodBalance.Sum monthHours = hours(month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).toList(),
                inPeriod, absent, file, actual, timeOff);
        List<LocalDate> earlierDays = period.days().stream().filter(day -> day.isBefore(month.atDay(1))).toList();
        // Zakończone miesiące przenoszą całe saldo, miesiąc w toku (i przyszłe) tylko nadwyżkę.
        YearMonth current = YearMonth.now(StoreTime.ZONE);
        List<LocalDate> earlierClosed = earlierDays.stream().filter(day -> YearMonth.from(day).isBefore(current)).toList();
        List<LocalDate> earlierOpen = earlierDays.stream().filter(day -> !YearMonth.from(day).isBefore(current)).toList();
        Integer carried = earlierDays.isEmpty() ? null : PeriodBalance.carried(
                earlierClosed.isEmpty() ? null : hours(earlierClosed, inPeriod, absent, file, actual, timeOff),
                earlierOpen.isEmpty() ? null : hours(earlierOpen, inPeriod, absent, file, actual, timeOff));
        List<LocalDate> laterDays = period.days().stream().filter(day -> day.isAfter(month.atEndOfMonth())).toList();
        PeriodBalance.Sum later = laterDays.isEmpty() ? null : hours(laterDays, inPeriod, absent, file, actual, timeOff);
        LocalDate actualUntil = actual.keySet().stream().max(Comparator.naturalOrder()).orElse(null);
        Set<LocalDate> leaveDays = inPeriod.stream().map(Planned::day).filter(day -> onLeave(absent, day))
                .collect(Collectors.toSet());
        List<Shift> toCheck = history.stream().filter(planned -> !leaveDays.contains(planned.day()))
                .map(Planned::shift).toList();
        List<ScheduleRules.DayOff> compensating = offHistory.stream().filter(off -> off.kind() == ScheduleDayOffKind.WN)
                .map(off -> new ScheduleRules.DayOff(off.day(), off.forDate())).toList();
        Set<LocalDate> compensated = compensating.stream().map(ScheduleRules.DayOff::forDate).collect(Collectors.toSet());
        List<LocalDate> owed = civil ? List.of() : toCheck.stream().map(Shift::day)
                .filter(day -> ScheduleRules.needsDayOff(day) && !compensated.contains(day)
                        && !day.isBefore(period.start().minusDays(ScheduleRules.SUNDAY_DAY_OFF_DAYS)))
                .sorted().toList();
        List<ScheduleResponse.Warning> warnings = civil ? List.of()
                : ScheduleRules.check(period, toCheck, day -> dailyLimit(file, day),
                        anyEmployment ? periodHours.norm() : null, periodHours.worked(),
                        new ScheduleRules.Days(
                                day -> history.stream().anyMatch(planned -> planned.day().equals(day)
                                        && open.contains(new OpenDay(planned.store().getId(), day))),
                                compensating, flexibleTime.findActive(store.getId()).isPresent())).stream()
                        .filter(warning -> warning.day() == null || YearMonth.from(warning.day()).equals(month))
                        .map(warning -> new ScheduleResponse.Warning(warning.day(), warning.message(), warning.basis()))
                        .toList();
        int toCompensate = periodHours.toCompensate();
        if (!civil && toCompensate > 0) {
            warnings = Stream.concat(warnings.stream(), Stream.of(new ScheduleResponse.Warning(null,
                    "Nadgodziny dobowe do odebrania: " + hoursText(toCompensate) + " h. Czas wolny na wniosek "
                            + "godzina za godzinę, z inicjatywy pracodawcy półtorej za godzinę najpóźniej do "
                            + period.end().format(DATE) + ", inaczej dodatek",
                    "art. 151 § 1 pkt 2, art. 151¹, art. 151² KP"))).toList();
        }
        if (!civil && toCompensate < 0) {
            warnings = Stream.concat(warnings.stream(), Stream.of(new ScheduleResponse.Warning(null,
                    "Czasu wolnego za nadgodziny o " + hoursText(-toCompensate)
                            + " h więcej, niż rozliczają nadgodziny dobowe z kart",
                    "art. 151² KP"))).toList();
        }
        if (!civil && !civilShifts.isEmpty()) {
            warnings = Stream.concat(warnings.stream(), ScheduleRules
                    .civilWarnings(toCheck, civilShifts.stream().map(Planned::shift).toList()).stream()
                    .filter(warning -> YearMonth.from(warning.day()).equals(month))
                    .map(warning -> new ScheduleResponse.Warning(warning.day(), warning.message(), warning.basis())))
                    .toList();
        }
        // Bez żadnej umowy w Kadrach nie wiadomo, czego brakuje, więc wyszarzenie całego miesiąca niczego by nie mówiło.
        List<LocalDate> outOfContract = file.contracts().isEmpty() ? List.of()
                : month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1))
                        .filter(day -> file.currentContract(day) == null).toList();
        List<ScheduleResponse.Warning> contractWarnings = inPeriod.stream().map(Planned::day)
                .filter(outOfContract::contains).sorted()
                .map(day -> new ScheduleResponse.Warning(day, "Zmiana poza umową", "daty umowy w Kadrach")).toList();
        ScheduleResponse.Pay pay = rate == null || rate.isEmpty() ? null
                : pay(rate.get(), month, inPeriod, absent, file, monthHours, toCompensate);
        return new ScheduleResponse.Row(employee.getId(), employee.getDisplayName(),
                employee.getPosition().getId(), employee.getPosition().getName(), groupId,
                employee.getStore().getNumber(), guest(employee, store, department), contract(file, month.atDay(1), month.atEndOfMonth()),
                monthHours.worked(), anyEmployment ? monthHours.norm() : null, periodHours.worked(),
                anyEmployment ? periodHours.norm() : null,
                fixed.stream().map(shift -> new ShiftResponse(shift.getDayOfWeek(), time(shift.getStart()),
                        time(shift.getEnd()))).toList(),
                cells, Stream.concat(contractWarnings.stream(), warnings.stream()).toList(), owed, outOfContract,
                anyEmployment ? PeriodBalance.monthTarget(monthHours, carried == null ? 0 : carried, later) : null,
                anyEmployment ? carried : null,
                periodHours.dailyOvertime(), actualUntil, periodHours.timeOff(), anyEmployment ? toCompensate : null,
                false, rate == null ? null : rate.orElse(null),
                pay == null ? null : pay.work().add(pay.leave()).add(pay.sick()),
                pay == null ? null : pay.overtimeSupplement(), pay);
    }

    /**
     * Brutto miesiąca na umowie o pracę i netto z Kadr. Urlop przy stawce godzinowej to stawka razy godziny zmian pod
     * nim w grafiku (rozporządzenie o wynagrodzeniu za urlop, § 6: składniki stałe za czas urlopu), siła wyższa połowa
     * (art. 148¹ § 1 KP), urlop opiekuńczy i bezpłatny nic. L4 to wynagrodzenie chorobowe 80% (art. 92 § 1 KP), tu
     * w przybliżeniu za godziny zmian pod zwolnieniem, a nie ze średniej z 12 miesięcy; ponad 33 dni w roku płaci ZUS.
     */
    private static ScheduleResponse.Pay pay(BigDecimal rate, YearMonth month, List<Planned> inPeriod,
            List<StaffLeave> absent, StaffFile file, PeriodBalance.Sum monthHours, int toCompensate) {
        BigDecimal supplement = supplement(monthHours.dailyOvertime(), toCompensate, rate);
        BigDecimal work = money(monthHours.worked() + monthHours.dailyOvertime(), rate).add(supplement);
        int leaveMinutes = 0;
        int halfMinutes = 0;
        int sickMinutes = 0;
        for (Planned planned : inPeriod) {
            if (!YearMonth.from(planned.day()).equals(month) || employment(file, planned.day()) == null) {
                continue;
            }
            StaffLeave leave = leaveOn(absent, planned.day());
            if (leave == null) {
                continue;
            }
            int minutes = planned.shift().minutes();
            switch (leave.getKind()) {
                case ANNUAL, ON_DEMAND, CHILD_CARE, OCCASIONAL, REHABILITATION -> leaveMinutes += minutes;
                case FORCE_MAJEURE -> halfMinutes += minutes;
                case SICK -> sickMinutes += minutes;
                default -> {
                }
            }
        }
        BigDecimal leavePay = money(leaveMinutes, rate)
                .add(money(halfMinutes, rate).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
        BigDecimal sickPay = money(sickMinutes, rate).multiply(new BigDecimal("0.8")).setScale(2, RoundingMode.HALF_UP);
        NetPay.Result net = NetPay.employment(work.add(leavePay), sickPay, NetPay.Settings.of(file.profile(), month));
        return new ScheduleResponse.Pay(work, supplement, leavePay, leaveMinutes + halfMinutes, sickPay, sickMinutes,
                net.social(), net.health(), net.tax(), net.net(), net.notes());
    }

    /** Minuty razy stawka za godzinę, do grosza. */
    private static BigDecimal money(int minutes, BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * Dodatek 50% za nadgodziny dobowe miesiąca, których nie rozliczył czas wolny (art. 151¹ § 1 pkt 2). Szacunek: nie
     * rozróżnia nocy, niedziel i świąt (dodatek 100%), a czas wolny liczy się w całym okresie.
     */
    private static BigDecimal supplement(int monthDailyOvertime, int toCompensate, BigDecimal rate) {
        int minutes = Math.min(monthDailyOvertime, Math.max(0, toCompensate));
        return money(minutes, rate).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /** Święto czynne w danym sklepie firmy. */
    private record OpenDay(UUID storeId, LocalDate day) {
    }

    private Set<OpenDay> openDays(Store store, LocalDate from, LocalDate to) {
        List<UUID> storeIds = store.getNip() == null ? List.of(store.getId())
                : stores.findActiveByNip(store.getNip()).stream().map(Store::getId).toList();
        return openHolidays.findActive(storeIds, from, to).stream()
                .map(holiday -> new OpenDay(holiday.getStore().getId(), holiday.getDay()))
                .collect(Collectors.toSet());
    }

    /**
     * Saldo dni: przepracowane z kart za dni z kartą, zaplanowane bez nieobecności za pozostałe, i wymiar dzień po dniu
     * z etatem tego dnia, minus nieobecność (art. 130 § 3). Liczy {@link PeriodBalance}.
     */
    private static PeriodBalance.Sum hours(List<LocalDate> days, List<Planned> planned, List<StaffLeave> absent,
            StaffFile file, Map<LocalDate, Integer> actual, Map<LocalDate, StaffOvertimeTimeOff> timeOff) {
        Map<LocalDate, Planned> byDay = planned.stream()
                .collect(Collectors.toMap(Planned::day, Function.identity(), (a, b) -> a));
        List<PeriodBalance.Day> result = new ArrayList<>();
        for (LocalDate day : days) {
            Planned shift = byDay.get(day);
            boolean leave = onLeave(absent, day);
            Integer plannedMinutes = shift != null && !leave ? shift.shift().minutes() : null;
            double norm = 0;
            StaffContract contract = employment(file, day);
            if (contract != null) {
                StaffFile.Fte fte = file.fteOn(contract, day);
                double share = (double) fte.numerator() / fte.denominator() * dailyLimit(file, day) / (8 * 60);
                norm = ScheduleCalendar.normMinutes(day) * share;
                // Dniem urlopu jest tylko dzień z zaplanowaną zmianą: obniża wymiar o pełny dobowy wymiar pracownika
                // (8 h razy etat, art. 130 § 3 i art. 154²), bez względu na długość zmiany. Dzień bez godzin nic.
                // L4 obniża wymiar o godziny z rozkładu, których nie przepracowano (art. 130 § 3 KP).
                if (leave && shift != null) {
                    norm -= leaveOn(absent, day).getKind() == LeaveKind.SICK ? shift.shift().minutes()
                            : 8 * 60 * share;
                }
            }
            StaffOvertimeTimeOff free = timeOff.get(day);
            int freeMinutes = free == null ? 0 : free.getMinutes();
            result.add(new PeriodBalance.Day(day, norm, plannedMinutes, actual.get(day), dailyLimit(file, day),
                    freeMinutes, free == null ? 0 : free.getInitiative().compensates(freeMinutes)));
        }
        return PeriodBalance.sum(result);
    }

    private static String hoursText(int minutes) {
        return minutes % 60 == 0 ? String.valueOf(minutes / 60) : minutes / 60 + ":" + String.format("%02d", minutes % 60);
    }

    private static StaffContract employment(StaffFile file, LocalDate day) {
        StaffContract contract = file.currentContract(day);
        return contract != null && contract.isEmployment() ? contract : null;
    }

    /** 7 godzin przy znacznym stopniu bez zgody lekarza (art. 15 ust. 2 ustawy o rehabilitacji), jak w Kadrach. */
    private static int dailyLimit(StaffFile file, LocalDate day) {
        StaffDisability disability = file.disabilityOn(day);
        return disability != null && !disability.isDoctorConsent() && disability.isSignificant() ? 7 * 60 : 8 * 60;
    }

    private static boolean onLeave(List<StaffLeave> absent, LocalDate day) {
        return leaveOn(absent, day) != null;
    }

    private static StaffLeave leaveOn(List<StaffLeave> absent, LocalDate day) {
        return absent.stream().filter(leave -> !day.isBefore(leave.getStartDate()) && !day.isAfter(leave.getEndDate()))
                .findFirst().orElse(null);
    }

    /** Urlop wypoczynkowy i na żądanie to nie dane szczególne; pozostałe rodzaje tylko dla osób z dostępem do Kadr. */
    private static String absence(StaffLeave leave, boolean records) {
        LeaveKind kind = leave.getKind();
        return records || kind == LeaveKind.ANNUAL || kind == LeaveKind.ON_DEMAND || kind == LeaveKind.B2B_PAID
                ? kind.name() : "OTHER";
    }

    private static boolean seesRecords(AppUserPrincipal principal) {
        return has(principal, AppUserPrincipal.OWNER_AUTHORITY) || has(principal, "STAFF_RECORDS:VIEW")
                || has(principal, "STAFF_RECORDS:EDIT");
    }

    /** Etat z końca miesiąca, bo aneks w jego trakcie obowiązuje dalej; wymiar i tak liczy się dzień po dniu. */
    private static String contract(StaffFile file, LocalDate first, LocalDate last) {
        StaffContract contract = file.currentContract(last);
        if (contract == null) {
            contract = file.currentContract(first);
        }
        if (contract == null) {
            return null;
        }
        if (contract.getKind() != ContractKind.EMPLOYMENT) {
            return contract.getKind() == ContractKind.B2B ? "B2B" : "zlecenie";
        }
        LocalDate day = contract.activeOn(last) ? last : first;
        StaffFile.Fte fte = file.fteOn(contract, day);
        return fte.numerator() == fte.denominator() ? "etat" : fte.numerator() + "/" + fte.denominator();
    }

    private static List<ScheduleResponse.Cell> cells(List<LocalDate> days, List<Planned> own, List<Planned> away,
            List<PlannedOff> offs, List<StaffLeave> absent, Store store, boolean records,
            Map<LocalDate, StaffOvertimeTimeOff> timeOff) {
        Map<LocalDate, Planned> byDay = new HashMap<>();
        away.forEach(planned -> byDay.put(planned.day(), planned));
        own.forEach(planned -> byDay.put(planned.day(), planned));
        Map<LocalDate, PlannedOff> offByDay = offs.stream()
                .collect(Collectors.toMap(PlannedOff::day, Function.identity(), (first, second) -> first));
        List<ScheduleResponse.Cell> result = new ArrayList<>();
        for (LocalDate day : days) {
            Planned planned = byDay.get(day);
            StaffLeave leaveOn = leaveOn(absent, day);
            boolean leave = leaveOn != null;
            PlannedOff off = offByDay.get(day);
            StaffOvertimeTimeOff free = timeOff.get(day);
            if (planned == null && !leave && off == null && free == null) {
                continue;
            }
            boolean other = planned != null && !planned.store().getId().equals(store.getId());
            result.add(new ScheduleResponse.Cell(day, planned == null ? null : time(planned.start()),
                    planned == null ? null : time(planned.end()), other ? planned.store().getId() : null,
                    other ? planned.store().getNumber() : null, leave, leave ? absence(leaveOn, records) : null,
                    off == null ? null : off.kind(),
                    off == null ? null : off.forDate(),
                    leave && records ? new StaffRecordResponse.Leave(leaveOn.getId(), leaveOn.getKind(),
                            leaveOn.getStartDate(), leaveOn.getEndDate(), leaveOn.getDays()) : null,
                    free == null ? null : free.getMinutes(), free == null ? null : free.getInitiative(),
                    planned == null ? null : planned.positionId()));
        }
        return result;
    }

    /** Najmniejsza i docelowa obsada stanowisk działu w sklepie, po stanowisku. */
    private Map<UUID, StaffPositionMinimum> staffing(UUID storeId, UUID departmentId) {
        return minimums.findActive(storeId, departmentId).stream()
                .collect(Collectors.toMap(minimum -> minimum.getPosition().getId(), Function.identity()));
    }

    private List<ScheduleResponse.Position> positions(Store store, StaffDepartment department,
            List<StaffPosition> departmentPositions, List<LocalDate> days, List<ScheduleResponse.Row> rows) {
        Map<UUID, StaffPositionMinimum> staffing = staffing(store.getId(), department.getId());
        List<UUID> ids = departmentPositions.stream().map(StaffPosition::getId).toList();
        Map<UUID, List<StaffShift>> options = ids.isEmpty() ? Map.of()
                : shifts.findActiveOfPositions(ids, store.getId()).stream()
                        .collect(Collectors.groupingBy(shift -> shift.getPosition().getId()));
        return departmentPositions.stream().map(position -> {
            // Osoba z umową o pracę i zleceniem tego dnia to jedna osoba na sali.
            List<Integer> counts = days.stream().map(day -> (int) rows.stream()
                    .filter(row -> row.cells().stream().anyMatch(cell -> cell.date().equals(day)
                            && cell.start() != null && cell.store() == null && !cell.absent()
                            && position.getId().equals(cell.positionId() != null ? cell.positionId() : row.positionId())))
                    .map(ScheduleResponse.Row::employeeId).distinct().count()).toList();
            StaffPositionMinimum count = staffing.get(position.getId());
            return new ScheduleResponse.Position(position.getId(), position.getName(),
                    count == null ? null : count.getMinCount(), count == null ? null : count.getTargetCount(),
                    options.getOrDefault(position.getId(), List.of()).stream()
                            .map(shift -> new ShiftResponse(shift.getDayOfWeek(), time(shift.getStart()),
                                    time(shift.getEnd())))
                            .toList(),
                    counts);
        }).toList();
    }

    /** Pracownicy działu w tym sklepie i wszyscy, którzy mają w tym grafiku dni: goście i ci, którzy odeszli. */
    private List<StaffEmployee> team(Store store, StaffDepartment department, Set<UUID> planned) {
        Map<UUID, StaffEmployee> team = new HashMap<>();
        employees.findInStores(List.of(store.getId())).stream()
                .filter(employee -> employee.getEndedAt() == null
                        && employee.getPosition().getDepartment().getId().equals(department.getId()))
                .forEach(employee -> team.put(employee.getId(), employee));
        planned.stream().filter(id -> !team.containsKey(id))
                .forEach(id -> employees.findWithDetails(id).ifPresent(employee -> team.put(id, employee)));
        Map<UUID, Integer> order = scheduleMembers.find(store.getId(), department.getId()).stream().collect(
                Collectors.toMap(member -> member.getEmployee().getId(), StaffScheduleMember::getPosition));
        return team.values().stream()
                .sorted(Comparator.comparing((StaffEmployee employee) -> guest(employee, store, department))
                        .thenComparing(employee -> order.getOrDefault(employee.getId(), Integer.MAX_VALUE))
                        .thenComparing(employee -> employee.getPosition().getName().toLowerCase())
                        .thenComparing(employee -> employee.getDisplayName().toLowerCase()))
                .toList();
    }

    private static boolean guest(StaffEmployee employee, Store store, StaffDepartment department) {
        return !employee.getStore().getId().equals(store.getId()) || employee.getEndedAt() != null
                || !employee.getPosition().getDepartment().getId().equals(department.getId());
    }

    private Map<UUID, StaffFile> files(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StaffContract>> byContract = contracts.findActive(ids).stream()
                .collect(Collectors.groupingBy(contract -> contract.getEmployee().getId()));
        Map<UUID, List<StaffContractChange>> byChange = contractChanges.findActive(ids).stream()
                .collect(Collectors.groupingBy(change -> change.getEmployee().getId()));
        Map<UUID, List<StaffDisability>> byDisability = disabilities.findActive(ids).stream()
                .collect(Collectors.groupingBy(disability -> disability.getEmployee().getId()));
        Map<UUID, StaffFile> result = new HashMap<>();
        for (UUID id : ids) {
            result.put(id, new StaffFile(null, byContract.getOrDefault(id, List.of()),
                    byChange.getOrDefault(id, List.of()), List.of(), byDisability.getOrDefault(id, List.of()),
                    List.of(), null, List.of()));
        }
        return result;
    }

    private StaffScheduleEntry entry(ScheduleChangeRequest.Change change, Store store, StaffDepartment department,
            Map<UUID, Store> allowed, AppUser actor) {
        if (change.end() == null) {
            throw ApiException.badRequest("Podaj godzinę końca zmiany");
        }
        LocalTime start = parseTime(change.start());
        LocalTime end = parseTime(change.end());
        if (start.equals(end)) {
            throw ApiException.badRequest("Godziny pracy: początek i koniec nie mogą być takie same");
        }
        requireCivilFits(change, start, end);
        Store target = change.storeId() == null ? store : allowed.get(change.storeId());
        if (target == null) {
            throw ApiException.badRequest("Wybrany sklep nie należy do firmy albo nie masz do niego dostępu");
        }
        // Święto zamknięte to dzień wolny od pracy: zmianę da się ułożyć dopiero po oznaczeniu sklepu jako czynnego.
        if (ScheduleCalendar.holiday(change.date()) != null && openHolidays
                .findActive(List.of(target.getId()), change.date(), change.date()).isEmpty()) {
            throw ApiException.conflict(ScheduleCalendar.holiday(change.date()) + ": sklep " + target.getNumber()
                    + " jest zamknięty. Żeby ułożyć zmianę, oznacz go jako czynny w nagłówku dnia.");
        }
        StaffSchedule schedule = schedule(target, department, change.date().withDayOfMonth(1), actor);
        StaffEmployee employee = employees.getReferenceById(change.employeeId());
        return new StaffScheduleEntry(schedule, employee, change.date(), start, end, change.isCivil(),
                change.positionId(), actor);
    }

    /**
     * W5 jest bez daty, a WN wskazuje niedzielę albo święto; samej niedzieli i święta nie oznacza się jako W5 ani WN. Zwykłe
     * W (bez daty) można postawić każdego dnia, także w niedzielę, żeby pracownik widział, że ma wolne.
     */
    private StaffScheduleDayOff dayOff(ScheduleChangeRequest.Change change, Store store, StaffDepartment department,
            AppUser actor) {
        LocalDate day = change.date();
        boolean compensating = change.dayOff() == ScheduleDayOffKind.WN;
        if (compensating != (change.forDate() != null)) {
            throw ApiException.badRequest("WN wymaga niedzieli albo święta, a W5 i W nie mają daty");
        }
        if (change.dayOff() != ScheduleDayOffKind.W && ScheduleRules.needsDayOff(day)) {
            throw ApiException.badRequest("Niedzieli ani święta nie oznacza się jako W5 czy WN");
        }
        if (compensating && !ScheduleRules.needsDayOff(change.forDate())) {
            throw ApiException.badRequest("WN przysługuje za niedzielę albo święto");
        }
        if (compensating && daysOff.existsFor(change.employeeId(), change.forDate())) {
            throw ApiException.conflict("Za ten dzień jest już oznaczone WN");
        }
        StaffSchedule schedule = schedule(store, department, day.withDayOfMonth(1), actor);
        return new StaffScheduleDayOff(schedule, employees.getReferenceById(change.employeeId()), day, change.dayOff(),
                change.forDate(), actor);
    }

    private StaffSchedule schedule(Store store, StaffDepartment department, LocalDate month, AppUser actor) {
        return schedules.find(store.getId(), department.getId(), month)
                .orElseGet(() -> schedules.saveAndFlush(new StaffSchedule(store, department, month, actor)));
    }

    private StaffScheduleEntry current(UUID employeeId, LocalDate day) {
        return entries.findOfEmployees(List.of(employeeId), day, day).stream().findFirst().orElse(null);
    }

    private StaffScheduleEntry currentCivil(UUID employeeId, LocalDate day) {
        return entries.findCivilOfEmployees(List.of(employeeId), day, day).stream().findFirst().orElse(null);
    }

    /**
     * Zmiana na zleceniu wymaga zlecenia w Kadrach tego dnia i nie może nachodzić na zmianę na umowie o pracę (to te
     * same godziny tej samej osoby), także w drugą stronę.
     */
    private void requireCivilFits(ScheduleChangeRequest.Change change, LocalTime start, LocalTime end) {
        UUID employeeId = change.employeeId();
        LocalDate day = change.date();
        if (change.isCivil() && contracts.findActive(List.of(employeeId)).stream()
                .noneMatch(contract -> contract.getKind() == ContractKind.MANDATE && contract.activeOn(day))) {
            throw ApiException.badRequest("Ten pracownik nie ma " + day.format(DATE) + " umowy zlecenia w Kadrach");
        }
        StaffScheduleEntry other = change.isCivil() ? current(employeeId, day) : currentCivil(employeeId, day);
        if (other != null && overlaps(start, end, other.getStart(), other.getEnd())) {
            throw ApiException.conflict("Godziny " + (change.isCivil() ? "zlecenia" : "umowy o pracę")
                    + " nachodzą na godziny " + (change.isCivil() ? "umowy o pracę " : "zlecenia ")
                    + time(other.getStart()) + "–" + time(other.getEnd()));
        }
    }

    private static boolean overlaps(LocalTime start, LocalTime end, LocalTime otherStart, LocalTime otherEnd) {
        int from = start.toSecondOfDay() / 60;
        int to = end.isAfter(start) ? end.toSecondOfDay() / 60 : end.toSecondOfDay() / 60 + 24 * 60;
        int otherFrom = otherStart.toSecondOfDay() / 60;
        int otherTo = otherEnd.isAfter(otherStart) ? otherEnd.toSecondOfDay() / 60
                : otherEnd.toSecondOfDay() / 60 + 24 * 60;
        return from < otherTo && otherFrom < to;
    }

    private StaffScheduleDayOff currentOff(UUID employeeId, LocalDate day) {
        return daysOff.findOfEmployees(List.of(employeeId), day, day).stream().findFirst().orElse(null);
    }

    private static boolean same(StaffScheduleEntry entry, ScheduleChangeRequest.Change change, Store store) {
        UUID target = change.storeId() == null ? store.getId() : change.storeId();
        return change.start() != null && change.end() != null && entry.getSchedule().getStore().getId().equals(target)
                && entry.getStart().equals(parseTime(change.start())) && entry.getEnd().equals(parseTime(change.end()))
                && Objects.equals(entry.getPositionId(), change.positionId());
    }

    /** Pracownik → stanowiska działu poza stałym, które umie obsłużyć (tylko je wpisuje się na dzień). */
    private Map<UUID, Set<UUID>> dayPositions(Collection<UUID> employeeIds, StaffDepartment department) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        return employeePositions.findActive(employeeIds).stream()
                .filter(skill -> skill.getPosition().getDepartment().getId().equals(department.getId()))
                .collect(Collectors.groupingBy(skill -> skill.getEmployee().getId(),
                        Collectors.mapping(skill -> skill.getPosition().getId(), Collectors.toCollection(LinkedHashSet::new))));
    }

    /**
     * Stanowisko dnia do zapisu: stałe albo puste = puste, inne tylko z listy stanowisk pracownika w tym dziale. Dni wolne
     * i wiersz zlecenia stanowiska nie mają.
     */
    private static UUID dayPosition(StaffEmployee employee, ScheduleChangeRequest.Change change,
            Map<UUID, Set<UUID>> skills) {
        UUID position = change.positionId();
        if (position == null || change.start() == null || change.isCivil()
                || position.equals(employee.getPosition().getId())) {
            return null;
        }
        if (!skills.getOrDefault(employee.getId(), Set.of()).contains(position)) {
            throw ApiException.conflict(employee.getDisplayName()
                    + " nie ma tego stanowiska na liście swoich stanowisk. Dodaj je w Pracownikach.");
        }
        return position;
    }

    /** Dzień w innym dziale albo sklepie bez dostępu należy do kogoś innego: nie nadpisujemy go po cichu. */
    private static void requireOwned(StaffSchedule schedule, LocalDate day, StaffDepartment department,
            Map<UUID, Store> allowed, StaffEmployee employee) {
        if (!schedule.getDepartment().getId().equals(department.getId())) {
            throw ApiException.conflict(employee.getDisplayName() + " ma " + day.format(DATE)
                    + " wpis w dziale " + schedule.getDepartment().getName());
        }
        if (!allowed.containsKey(schedule.getStore().getId())) {
            throw ApiException.conflict(employee.getDisplayName() + " ma " + day.format(DATE)
                    + " wpis w sklepie " + schedule.getStore().getNumber());
        }
    }

    private boolean changedSince(StaffScheduleVersion version, List<StaffScheduleEntry> active,
            List<StaffScheduleDayOff> activeOff) {
        Set<Planned> published = versionEntries.findEntries(version.getId()).stream().map(Planned::new)
                .collect(Collectors.toSet());
        Set<PlannedOff> publishedOff = versionDaysOff.findOfVersion(version.getId()).stream().map(PlannedOff::new)
                .collect(Collectors.toSet());
        return !published.equals(active.stream().map(Planned::new).collect(Collectors.toSet()))
                || !publishedOff.equals(activeOff.stream().map(PlannedOff::new).collect(Collectors.toSet()));
    }

    private Map<UUID, Store> allowedStores(Store store, UUID actorId) {
        Set<UUID> ids = employeeService.companyStores(store.getId(), actorId).stream().map(StoreSummary::id)
                .collect(Collectors.toSet());
        return stores.findAllById(ids).stream().collect(Collectors.toMap(Store::getId, Function.identity()));
    }

    private List<StaffPosition> activePositions(Store store, UUID departmentId) {
        return positions.findActive(store.companyKey()).stream()
                .filter(position -> position.getDepartment().getId().equals(departmentId)).toList();
    }

    private void requireEdit(StaffDepartment department, AppUserPrincipal principal) {
        if (!canEdit(department, principal)) {
            throw ApiException.forbidden("Grafik tego działu układa jego kierownik");
        }
    }

    private boolean canEdit(StaffDepartment department, AppUserPrincipal principal) {
        return has(principal, "STAFF_SCHEDULE:EDIT") && (canEditAll(principal)
                || managers.findDepartmentIds(principal.getId()).contains(department.getId()));
    }

    private static boolean canEditAll(AppUserPrincipal principal) {
        return has(principal, AppUserPrincipal.OWNER_AUTHORITY) || has(principal, STAFF_EDIT);
    }

    private static boolean has(AppUserPrincipal principal, String authority) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }

    private static ScheduleResponse.Version version(StaffScheduleVersion version, LocalDate first) {
        Instant deadline = first.minusDays(NOTICE_DAYS).plusDays(1).atStartOfDay(StoreTime.ZONE).toInstant();
        return new ScheduleResponse.Version(version.getNumber(), version.getPublishedAt(),
                version.getPublishedBy() == null ? null : version.getPublishedBy().getDisplayName(),
                version.getNumber() == 1 && !version.getPublishedAt().isBefore(deadline));
    }

    private static ScheduleResponse.Day day(LocalDate day, boolean open) {
        return new ScheduleResponse.Day(day, ScheduleCalendar.holiday(day), ScheduleCalendar.isClosed(day) && !open);
    }

    private Store store(UUID storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie znaleziono sklepu"));
    }

    private StaffDepartment department(UUID storeId, UUID departmentId) {
        return departments.findInCompany(store(storeId).companyKey(), departmentId)
                .filter(department -> department.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono działu"));
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeException exception) {
            throw ApiException.badRequest("Miesiąc w formacie RRRR-MM");
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException exception) {
            throw ApiException.badRequest("Godzina w formacie GG:MM");
        }
    }

    private static String time(LocalTime value) {
        return value.format(TIME);
    }
}
