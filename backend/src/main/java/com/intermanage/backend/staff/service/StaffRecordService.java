package com.intermanage.backend.staff.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.common.component.StoreTime;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.component.LeaveCalculator;
import com.intermanage.backend.staff.component.Seniority;
import com.intermanage.backend.staff.component.StaffCompliance;
import com.intermanage.backend.staff.component.StaffCompliance.Severity;
import com.intermanage.backend.staff.component.StaffCompliance.Warning;
import com.intermanage.backend.staff.component.StaffFile;
import com.intermanage.backend.staff.component.StaffFile.Fte;
import com.intermanage.backend.staff.dto.ContractChangeRequest;
import com.intermanage.backend.staff.dto.ContractRequest;
import com.intermanage.backend.staff.dto.DisabilityRequest;
import com.intermanage.backend.staff.dto.LeaveOpeningRequest;
import com.intermanage.backend.staff.dto.LeaveRequest;
import com.intermanage.backend.staff.dto.StaffProfileRequest;
import com.intermanage.backend.staff.dto.StaffRecordResponse;
import com.intermanage.backend.staff.dto.StaffRecordRow;
import com.intermanage.backend.staff.dto.WorkPeriodRequest;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.EmploymentType;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffContractChange;
import com.intermanage.backend.staff.entity.StaffContractRevision;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffLeaveOpening;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffRecordEntry;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.StaffWorkPeriod;
import com.intermanage.backend.staff.entity.WorkPeriodKind;
import com.intermanage.backend.staff.mapper.StaffRecordMapper;
import com.intermanage.backend.staff.repository.StaffContractChangeRepository;
import com.intermanage.backend.staff.repository.StaffContractRepository;
import com.intermanage.backend.staff.repository.StaffContractRevisionRepository;
import com.intermanage.backend.staff.repository.StaffDisabilityRepository;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.repository.StaffLeaveOpeningRepository;
import com.intermanage.backend.staff.repository.StaffLeaveRepository;
import com.intermanage.backend.staff.repository.StaffProfileRepository;
import com.intermanage.backend.staff.repository.StaffShiftRepository;
import com.intermanage.backend.staff.repository.StaffWorkPeriodRepository;

/**
 * Kadry: profil pracownika z umowami, stażem, urlopem i orzeczeniem. Dostęp tylko z uprawnieniem STAFF_RECORDS i tylko
 * do pracowników ze sklepów, do których użytkownik ma dostęp. Każda zmiana zwraca cały profil.
 */
@Service
public class StaffRecordService {

    private final StaffEmployeeService employeeService;
    private final StaffEmployeeRepository employees;
    private final StaffProfileRepository profiles;
    private final StaffContractRepository contracts;
    private final StaffContractChangeRepository contractChanges;
    private final StaffContractRevisionRepository contractRevisions;
    private final StaffWorkPeriodRepository workPeriods;
    private final StaffDisabilityRepository disabilities;
    private final StaffLeaveRepository leaves;
    private final StaffLeaveOpeningRepository openings;
    private final StaffShiftRepository shifts;
    private final AppUserRepository users;
    private final StaffRecordMapper mapper;
    private final StaffLeaveDays leaveDays;

    public StaffRecordService(StaffEmployeeService employeeService, StaffEmployeeRepository employees,
            StaffProfileRepository profiles, StaffContractRepository contracts,
            StaffContractChangeRepository contractChanges, StaffContractRevisionRepository contractRevisions,
            StaffWorkPeriodRepository workPeriods, StaffDisabilityRepository disabilities, StaffLeaveRepository leaves, StaffLeaveOpeningRepository openings,
            StaffShiftRepository shifts, AppUserRepository users, StaffRecordMapper mapper,
            StaffLeaveDays leaveDays) {
        this.employeeService = employeeService;
        this.employees = employees;
        this.profiles = profiles;
        this.contracts = contracts;
        this.contractChanges = contractChanges;
        this.contractRevisions = contractRevisions;
        this.workPeriods = workPeriods;
        this.disabilities = disabilities;
        this.leaves = leaves;
        this.openings = openings;
        this.shifts = shifts;
        this.users = users;
        this.mapper = mapper;
        this.leaveDays = leaveDays;
    }

    @Transactional(readOnly = true)
    public List<StaffRecordRow> list(UUID storeId, UUID actorId) {
        List<StaffEmployee> all = employees.findInStores(employeeService.companyStoreIds(storeId, actorId));
        Map<UUID, StaffFile> files = files(all.stream().map(StaffEmployee::getId).toList());
        LocalDate today = today();
        return all.stream().map(employee -> row(employee, files.get(employee.getId()), today)).toList();
    }

    @Transactional(readOnly = true)
    public StaffRecordResponse get(UUID storeId, UUID id, Integer year, UUID actorId) {
        return response(employeeService.employeeInCompany(storeId, id, actorId), year);
    }

    @Transactional
    public StaffRecordResponse saveProfile(UUID storeId, UUID id, StaffProfileRequest request, UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        StaffProfile profile = profiles.findById(id).orElseGet(() -> new StaffProfile(id));
        if (request.firstJob() && workPeriods.findActive(List.of(id)).stream().anyMatch(StaffRecordService::earlierJob)) {
            throw ApiException.conflict("Pracownik ma wpisane wcześniejsze zatrudnienie, więc to nie jest pierwsza praca "
                    + "w życiu. Usuń ten okres albo nie zaznaczaj pierwszej pracy.");
        }
        profile.update(request.birthDate(), request.educationLevel(), request.educationEndDate(), request.firstJob(),
                request.medicalExamUntil(), request.bhpTrainingUntil(), request.sanitaryUntil(), actor(actorId));
        profiles.saveAndFlush(profile);
        return response(employee, null);
    }

    @Transactional
    public StaffRecordResponse saveContract(UUID storeId, UUID id, UUID contractId, ContractRequest request,
            UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        boolean employment = request.kind() == ContractKind.EMPLOYMENT;
        if (employment && request.employmentType() == null) {
            throw ApiException.badRequest("Wybierz rodzaj umowy o pracę");
        }
        boolean endRequired = employment && request.employmentType() != EmploymentType.INDEFINITE;
        if (endRequired && request.endDate() == null) {
            throw ApiException.badRequest("Umowa na okres próbny i na czas określony musi mieć datę końca");
        }
        requireOrder(request.startDate(), request.endDate());
        if (employment && request.fteNumerator() > request.fteDenominator()) {
            throw ApiException.badRequest("Etat nie może być większy niż pełny");
        }
        StaffContract contract = contractId == null ? new StaffContract(employee, actor(actorId))
                : entry(contracts, id, contractId, "umowy");
        boolean overlaps = contracts.findActive(List.of(id)).stream()
                .filter(other -> !other.getId().equals(contractId) && other.getKind() == request.kind())
                .anyMatch(other -> overlap(other, request));
        if (overlaps) {
            throw ApiException.conflict("Pracownik ma już umowę tego rodzaju w tym okresie");
        }
        if (contractId != null) {
            contractRevisions.save(new StaffContractRevision(contract, actor(actorId)));
        }
        contract.update(request.kind(), employment ? request.employmentType() : null, request.startDate(),
                request.endDate(), employment ? request.fteNumerator() : 1, employment ? request.fteDenominator() : 1,
                employment && request.fixedTermExempt());
        contracts.saveAndFlush(contract);
        return response(employee, null);
    }

    /** Aneks: nowy etat od wskazanego dnia, bez ruszania okresu przed nim. */
    @Transactional
    public StaffRecordResponse addContractChange(UUID storeId, UUID id, UUID contractId, ContractChangeRequest request,
            UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        StaffContract contract = entry(contracts, id, contractId, "umowy");
        if (!contract.isEmployment()) {
            throw ApiException.badRequest("Etat dotyczy tylko umowy o pracę");
        }
        if (request.fteNumerator() > request.fteDenominator()) {
            throw ApiException.badRequest("Etat nie może być większy niż pełny");
        }
        boolean inside = request.validFrom().isAfter(contract.getStartDate())
                && (contract.getEndDate() == null || !request.validFrom().isAfter(contract.getEndDate()));
        if (!inside) {
            throw ApiException.badRequest("Zmiana musi przypadać w trakcie umowy, po dniu jej rozpoczęcia");
        }
        boolean taken = contractChanges.findActive(List.of(id)).stream()
                .anyMatch(other -> other.getContract().getId().equals(contractId)
                        && other.getValidFrom().equals(request.validFrom()));
        if (taken) {
            throw ApiException.conflict("Ta umowa ma już zmianę od tego dnia");
        }
        contractChanges.saveAndFlush(new StaffContractChange(employee, contract, request.validFrom(),
                request.fteNumerator(), request.fteDenominator(), actor(actorId)));
        return response(employee, null);
    }

    @Transactional
    public StaffRecordResponse removeContractChange(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return remove(contractChanges, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse restoreContractChange(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return restore(contractChanges, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse saveWorkPeriod(UUID storeId, UUID id, UUID periodId, WorkPeriodRequest request,
            UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        requireOrder(request.startDate(), request.endDate());
        StaffWorkPeriod period = periodId == null ? new StaffWorkPeriod(employee, actor(actorId))
                : entry(workPeriods, id, periodId, "okresu");
        if (WorkPeriodKind.EMPLOYMENT == request.kind() || WorkPeriodKind.ABROAD == request.kind()) {
            requireNotFirstJob(id);
        }
        String employer = request.employer() == null || request.employer().isBlank() ? null : request.employer().strip();
        period.update(request.kind(), employer, request.startDate(), request.endDate(), request.documented());
        workPeriods.saveAndFlush(period);
        return response(employee, null);
    }

    @Transactional
    public StaffRecordResponse saveDisability(UUID storeId, UUID id, UUID disabilityId, DisabilityRequest request,
            UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        requireOrder(request.startDate(), request.endDate());
        StaffDisability disability = disabilityId == null ? new StaffDisability(employee, actor(actorId))
                : entry(disabilities, id, disabilityId, "orzeczenia");
        disability.update(request.degree(), request.startDate(), request.endDate(), request.doctorConsent());
        disabilities.saveAndFlush(disability);
        return response(employee, null);
    }

    @Transactional
    public StaffRecordResponse saveLeave(UUID storeId, UUID id, UUID leaveId, LeaveRequest request, UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        requireOrder(request.startDate(), request.endDate());
        if (request.startDate().getYear() != request.endDate().getYear()) {
            throw ApiException.badRequest("Urlop na przełomie lat wpisz osobno dla każdego roku");
        }
        boolean employed = contracts.findActive(List.of(id)).stream()
                .anyMatch(contract -> contract.isEmployment() && contract.activeOn(request.startDate()));
        if (!employed) {
            throw ApiException.conflict("Urlopy z Kodeksu pracy przysługują przy umowie o pracę, a w tym dniu jej nie ma");
        }
        // Urlop zużywa dni pracy z grafiku (art. 154² KP); bez ułożonych godzin ma 0 dni.
        int days = leaveDays.count(id, request.startDate(), request.endDate());
        // Limit z ustawy (np. 4 dni na żądanie, art. 167² KP): ponad niego to już nie ten rodzaj nieobecności.
        leaveDays.requireLimit(id, request.kind(), request.startDate().getYear(), leaveId, days);
        StaffLeave leave = leaveId == null ? new StaffLeave(employee, actor(actorId)) : entry(leaves, id, leaveId, "urlopu");
        leave.update(request.kind(), request.startDate(), request.endDate(), days);
        leaves.saveAndFlush(leave);
        return response(employee, request.startDate().getYear());
    }

    @Transactional
    public StaffRecordResponse saveOpening(UUID storeId, UUID id, LeaveOpeningRequest request, UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        AppUser actor = actor(actorId);
        openings.findActive(List.of(id)).forEach(opening -> opening.markDeleted(actor));
        openings.flush();
        openings.saveAndFlush(new StaffLeaveOpening(employee, request.year(), request.days(), actor));
        return response(employee, null);
    }

    @Transactional
    public StaffRecordResponse removeContract(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return remove(contracts, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse removeWorkPeriod(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return remove(workPeriods, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse removeDisability(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return remove(disabilities, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse removeLeave(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return remove(leaves, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse restoreContract(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return restore(contracts, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse restoreWorkPeriod(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        workPeriods.findById(entryId).filter(StaffRecordService::earlierJob).ifPresent(period -> requireNotFirstJob(id));
        return restore(workPeriods, storeId, id, entryId, actorId);
    }

    /**
     * Zatrudnienie u innego pracodawcy, także za granicą: przy nim nie ma pierwszej pracy w życiu (art. 153 § 1 KP).
     * Zlecenie i działalność nie są stosunkiem pracy, więc pierwszej pracy nie wykluczają.
     */
    private static boolean earlierJob(StaffWorkPeriod period) {
        return period.getKind() == WorkPeriodKind.EMPLOYMENT || period.getKind() == WorkPeriodKind.ABROAD;
    }

    private void requireNotFirstJob(UUID id) {
        if (profiles.findById(id).map(StaffProfile::isFirstJob).orElse(false)) {
            throw ApiException.conflict("Pracownik ma zaznaczoną pierwszą pracę w życiu, więc nie mógł być wcześniej "
                    + "zatrudniony. Odznacz ją w danych pracownika, zanim dodasz ten okres.");
        }
    }

    @Transactional
    public StaffRecordResponse restoreDisability(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return restore(disabilities, storeId, id, entryId, actorId);
    }

    @Transactional
    public StaffRecordResponse restoreLeave(UUID storeId, UUID id, UUID entryId, UUID actorId) {
        return restore(leaves, storeId, id, entryId, actorId);
    }

    private <T extends StaffRecordEntry> StaffRecordResponse remove(JpaRepository<T, UUID> repository, UUID storeId,
            UUID id, UUID entryId, UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        entry(repository, id, entryId, "wpisu").markDeleted(actor(actorId));
        repository.flush();
        return response(employee, null);
    }

    private <T extends StaffRecordEntry> StaffRecordResponse restore(JpaRepository<T, UUID> repository, UUID storeId,
            UUID id, UUID entryId, UUID actorId) {
        StaffEmployee employee = employeeService.employeeInCompany(storeId, id, actorId);
        repository.findById(entryId).filter(found -> found.getEmployee().getId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono wpisu"))
                .restore();
        repository.flush();
        return response(employee, null);
    }

    private <T extends StaffRecordEntry> T entry(JpaRepository<T, UUID> repository, UUID employeeId, UUID entryId,
            String what) {
        return repository.findById(entryId)
                .filter(found -> found.getEmployee().getId().equals(employeeId) && found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono " + what));
    }

    private StaffRecordResponse response(StaffEmployee employee, Integer requestedYear) {
        LocalDate today = today();
        int year = requestedYear == null ? today.getYear() : requestedYear;
        StaffFile file = files(List.of(employee.getId())).get(employee.getId());
        LocalDate seniorityDay = today;
        List<Warning> warnings = employee.getEndedAt() == null ? StaffCompliance.check(file, today) : List.of();
        return new StaffRecordResponse(mapper.toPerson(employee),
                file.profile() == null ? null : mapper.toProfile(file.profile()),
                file.contracts().stream().sorted(Comparator.comparing(StaffContract::getStartDate).reversed())
                        .map(contract -> mapper.toContract(contract, file.contractChanges().stream()
                                .filter(change -> change.getContract() == contract).toList()))
                        .toList(),
                file.workPeriods().stream().sorted(Comparator.comparing(StaffWorkPeriod::getStartDate).reversed())
                        .map(mapper::toWorkPeriod).toList(),
                file.disabilities().stream().sorted(Comparator.comparing(StaffDisability::getStartDate).reversed())
                        .map(mapper::toDisability).toList(),
                file.leaves().stream().filter(leave -> leave.getStartDate().getYear() == year)
                        .sorted(Comparator.comparing(StaffLeave::getStartDate).reversed())
                        .map(mapper::toLeave).toList(),
                file.opening() == null ? null : mapper.toOpening(file.opening()),
                new StaffRecordResponse.SeniorityInfo(Seniority.work(file, seniorityDay),
                        Seniority.inCompany(file, seniorityDay), Seniority.forLeave(file, seniorityDay),
                        Seniority.higherLeaveFrom(file, seniorityDay)),
                LeaveCalculator.forYear(file, year, today), warnings, StaffCompliance.rights(file, today));
    }

    private StaffRecordRow row(StaffEmployee employee, StaffFile file, LocalDate today) {
        StaffContract current = file.currentContract(today);
        boolean working = employee.getEndedAt() == null;
        List<Warning> warnings = working ? StaffCompliance.check(file, today) : List.of();
        Severity worst = warnings.isEmpty() ? null : warnings.getFirst().severity();
        Fte fte = current == null ? null : file.fteOn(current, today);
        Integer remaining = current != null && current.isEmployment()
                ? LeaveCalculator.forYear(file, today.getYear(), today).remainingDays() : null;
        return new StaffRecordRow(employee.getId(), employee.getUser().getDisplayName(), employee.getStore().getId(),
                employee.getStore().getNumber(), employee.getStore().getName(),
                employee.getPosition().getDepartment().getId(), employee.getPosition().getDepartment().getName(),
                employee.getPosition().getName(), employee.getEndedAt(),
                current == null ? null : current.getKind(), current == null ? null : current.getEmploymentType(),
                current == null ? null : current.getEndDate(), fte == null ? null : fte.numerator(),
                fte == null ? null : fte.denominator(), remaining, warnings.size(), worst);
    }

    private Map<UUID, StaffFile> files(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StaffProfile> profileById = profiles.findAllById(ids).stream()
                .collect(Collectors.toMap(StaffProfile::getEmployeeId, Function.identity()));
        Map<UUID, List<StaffContract>> contractsById = byEmployee(contracts.findActive(ids));
        Map<UUID, List<StaffContractChange>> changesById = byEmployee(contractChanges.findActive(ids));
        Map<UUID, List<StaffWorkPeriod>> periodsById = byEmployee(workPeriods.findActive(ids));
        Map<UUID, List<StaffDisability>> disabilitiesById = byEmployee(disabilities.findActive(ids));
        Map<UUID, List<StaffLeave>> leavesById = byEmployee(leaves.findActive(ids));
        Map<UUID, List<StaffLeaveOpening>> openingsById = byEmployee(openings.findActive(ids));
        Map<UUID, List<StaffShift>> shiftsById = shifts.findActiveOfEmployees(ids).stream()
                .collect(Collectors.groupingBy(shift -> shift.getEmployee().getId()));
        return ids.stream().collect(Collectors.toMap(Function.identity(), id -> new StaffFile(profileById.get(id),
                contractsById.getOrDefault(id, List.of()), changesById.getOrDefault(id, List.of()),
                periodsById.getOrDefault(id, List.of()),
                disabilitiesById.getOrDefault(id, List.of()), leavesById.getOrDefault(id, List.of()),
                openingsById.getOrDefault(id, List.of()).stream().findFirst().orElse(null),
                shiftsById.getOrDefault(id, List.of()))));
    }

    private static <T extends StaffRecordEntry> Map<UUID, List<T>> byEmployee(List<T> entries) {
        return entries.stream().collect(Collectors.groupingBy(entry -> entry.getEmployee().getId()));
    }

    private static boolean overlap(StaffContract other, ContractRequest request) {
        boolean startsBeforeEnd = request.endDate() == null || !other.getStartDate().isAfter(request.endDate());
        boolean endsAfterStart = other.getEndDate() == null || !other.getEndDate().isBefore(request.startDate());
        return startsBeforeEnd && endsAfterStart;
    }

    private static void requireOrder(LocalDate start, LocalDate end) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("Koniec nie może być przed początkiem");
        }
    }

    private AppUser actor(UUID actorId) {
        return users.getReferenceById(actorId);
    }

    private static LocalDate today() {
        return LocalDate.now(StoreTime.ZONE);
    }
}
