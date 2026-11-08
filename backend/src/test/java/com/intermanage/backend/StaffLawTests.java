package com.intermanage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.intermanage.backend.staff.component.LeaveCalculator;
import com.intermanage.backend.staff.component.LeaveCalculator.LeaveYear;
import com.intermanage.backend.staff.component.Seniority;
import com.intermanage.backend.staff.component.StaffCompliance;
import com.intermanage.backend.staff.component.StaffCompliance.Warning;
import com.intermanage.backend.staff.component.StaffFile;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.DisabilityDegree;
import com.intermanage.backend.staff.entity.EducationLevel;
import com.intermanage.backend.staff.entity.EmploymentType;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.StaffContract;
import com.intermanage.backend.staff.entity.StaffContractChange;
import com.intermanage.backend.staff.entity.StaffDisability;
import com.intermanage.backend.staff.entity.StaffLeave;
import com.intermanage.backend.staff.entity.StaffLeaveOpening;
import com.intermanage.backend.staff.entity.StaffProfile;
import com.intermanage.backend.staff.entity.StaffShift;
import com.intermanage.backend.staff.entity.StaffWorkPeriod;
import com.intermanage.backend.staff.entity.WorkPeriodKind;

/** Reguły stażu, urlopu i ostrzeżeń na przykładach z przepisów, bez bazy. */
class StaffLawTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private final List<StaffContract> contracts = new ArrayList<>();
    private final List<StaffContractChange> changes = new ArrayList<>();
    private final List<StaffWorkPeriod> periods = new ArrayList<>();
    private final List<StaffDisability> disabilities = new ArrayList<>();
    private final List<StaffLeave> leaves = new ArrayList<>();
    private final List<StaffShift> shifts = new ArrayList<>();
    private StaffProfile profile;
    private StaffLeaveOpening opening;

    @Test
    void fullYearGivesTwentyOrTwentySixDays() {
        employment("2024-01-01", null, 1, 1);
        assertThat(year(2026).annualDays()).isEqualTo(20);

        education(EducationLevel.HIGHER, null);
        assertThat(year(2026).fullDays()).isEqualTo(26);
    }

    @Test
    void partTimeIsProportionalAndRoundedUp() {
        employment("2024-01-01", null, 1, 2);
        education(EducationLevel.HIGHER, null);
        assertThat(year(2026).annualDays()).isEqualTo(13);

        contracts.clear();
        employment("2024-01-01", null, 3, 4);
        assertThat(year(2026).annualDays()).isEqualTo(20);
    }

    @Test
    void fteChangeDuringYearSplitsLeaveAndKeepsEarlierPeriod() {
        employment("2024-01-01", null, 1, 1);
        changes.add(new StaffContractChange(null, contracts.getFirst(), LocalDate.parse("2026-07-01"), 1, 2, null));
        assertThat(year(2026).annualDays()).isEqualTo(15);
        assertThat(year(2025).annualDays()).isEqualTo(20);
        assertThat(year(2027).annualDays()).isEqualTo(10);
    }

    @Test
    void fteOnDayComesFromLatestChangeBeforeIt() {
        employment("2024-01-01", null, 1, 1);
        StaffContract contract = contracts.getFirst();
        changes.add(new StaffContractChange(null, contract, LocalDate.parse("2026-03-01"), 1, 2, null));
        changes.add(new StaffContractChange(null, contract, LocalDate.parse("2026-09-01"), 3, 4, null));
        assertThat(file().fteOn(contract, LocalDate.parse("2026-02-28"))).isEqualTo(new StaffFile.Fte(1, 1));
        assertThat(file().fteOn(contract, LocalDate.parse("2026-03-01"))).isEqualTo(new StaffFile.Fte(1, 2));
        assertThat(file().fteOn(contract, LocalDate.parse("2026-11-01"))).isEqualTo(new StaffFile.Fte(3, 4));
    }

    @Test
    void fteChangeIsNotAnotherFixedTermContract() {
        for (String start : List.of("2024-01-01", "2024-07-01", "2025-01-01")) {
            contract(ContractKind.EMPLOYMENT, EmploymentType.FIXED, start, LocalDate.parse(start).plusMonths(6)
                    .minusDays(1).toString());
        }
        changes.add(new StaffContractChange(null, contracts.getLast(), LocalDate.parse("2025-03-01"), 1, 2, null));
        assertThat(messages()).noneMatch(message -> message.contains("umowa na czas określony"));
    }

    @Test
    void partOfYearCountsCalendarMonthsRoundedUp() {
        employment("2026-03-15", null, 1, 1);
        assertThat(year(2026).annualDays()).isEqualTo(17);
    }

    @Test
    void contractEndedDuringYearIsProportional() {
        employment("2024-01-01", "2026-06-10", 1, 1);
        assertThat(year(2026).annualDays()).isEqualTo(10);
    }

    @Test
    void firstJobEarnsLeaveMonthByMonth() {
        employment("2026-08-01", null, 1, 1);
        profile("firstJob");
        assertThat(year(2026).annualDays()).isEqualTo(5);
    }

    @Test
    void reachingTenYearsDuringYearGivesHigherLeaveForWholeYear() {
        employment("2016-09-01", null, 1, 1);
        assertThat(year(2026).fullDays()).isEqualTo(26);
        assertThat(year(2025).fullDays()).isEqualTo(20);
    }

    @Test
    void overlappingPeriodsCountOnce() {
        periods.add(period(WorkPeriodKind.EMPLOYMENT, "2020-01-01", "2020-12-31", false));
        periods.add(period(WorkPeriodKind.EMPLOYMENT, "2020-06-01", "2021-05-31", false));
        assertThat(Seniority.work(file(), TODAY).years()).isEqualTo(1);
        assertThat(Seniority.work(file(), TODAY).months()).isEqualTo(5);
    }

    @Test
    void schoolYearsAreNotAddedToWorkDoneDuringStudies() {
        periods.add(period(WorkPeriodKind.EMPLOYMENT, "2018-01-01", "2019-12-31", false));
        education(EducationLevel.HIGHER, "2022-06-30");
        assertThat(Seniority.forLeave(file(), TODAY).years()).isEqualTo(8);

        contracts.clear();
        periods.clear();
        periods.add(period(WorkPeriodKind.EMPLOYMENT, "2023-01-01", "2024-12-31", false));
        assertThat(Seniority.forLeave(file(), TODAY).years()).isEqualTo(10);
    }

    @Test
    void newPeriodsCountOnlyDocumentedAndFromMayTwentySix() {
        employment("2025-01-01", null, 1, 1);
        periods.add(period(WorkPeriodKind.MANDATE, "2015-01-01", "2024-12-31", false));
        assertThat(year(2026).fullDays()).isEqualTo(20);

        periods.clear();
        periods.add(period(WorkPeriodKind.MANDATE, "2015-01-01", "2024-12-31", true));
        assertThat(year(2026).fullDays()).isEqualTo(26);
        assertThat(Seniority.forLeave(file(), LocalDate.of(2026, 4, 30)).years()).isEqualTo(1);
        assertThat(year(2025).fullDays()).isEqualTo(20);
    }

    @Test
    void additionalLeaveAfterYearForModerateOrSevereDegree() {
        employment("2020-01-01", null, 1, 1);
        disability(DisabilityDegree.MODERATE, "2025-06-01", null, false);
        assertThat(year(2025).additionalDays()).isZero();
        assertThat(year(2026).additionalDays()).isEqualTo(10);

        disabilities.clear();
        disability(DisabilityDegree.LIGHT, "2020-01-01", null, false);
        assertThat(year(2026).additionalDays()).isZero();
    }

    @Test
    void additionalLeaveIsProportionalToFte() {
        employment("2020-01-01", null, 1, 2);
        disability(DisabilityDegree.SEVERE, "2020-01-01", null, false);
        assertThat(year(2026).additionalDays()).isEqualTo(5);
    }

    @Test
    void mandateAndB2bHaveNoLeaveFromLabourCode() {
        contract(ContractKind.MANDATE, null, "2020-01-01", null);
        contract(ContractKind.B2B, null, "2020-01-01", null);
        assertThat(year(2026).annualDays()).isZero();
    }

    @Test
    void unusedLeaveCarriesOverAndOnDemandCounts() {
        employment("2025-01-01", null, 1, 1);
        leave(LeaveKind.ANNUAL, "2025-07-01", 15);
        leave(LeaveKind.ON_DEMAND, "2026-02-02", 1);
        LeaveYear leave = year(2026);
        assertThat(leave.carriedDays()).isEqualTo(5);
        assertThat(leave.usedDays()).isEqualTo(1);
        assertThat(leave.remainingDays()).isEqualTo(24);
    }

    @Test
    void openingStartsBalanceFromGivenYear() {
        employment("2010-01-01", null, 1, 1);
        opening = new StaffLeaveOpening(null, 2026, 7, null);
        assertThat(year(2026).carriedDays()).isEqualTo(7);
    }

    @Test
    void overdueLeaveAfterSeptemberThirtieth() {
        employment("2025-01-01", null, 1, 1);
        assertThat(LeaveCalculator.overdueDays(file(), 2026, TODAY)).isEqualTo(20);
        assertThat(LeaveCalculator.overdueDays(file(), 2026, LocalDate.of(2026, 9, 30))).isZero();
        assertThat(messages()).anyMatch(message -> message.contains("po terminie: 20"));
    }

    @Test
    void fourthFixedTermContractBecomesIndefinite() {
        for (String start : List.of("2024-01-01", "2024-07-01", "2025-01-01", "2025-07-01")) {
            contract(ContractKind.EMPLOYMENT, EmploymentType.FIXED, start, LocalDate.parse(start).plusMonths(6)
                    .minusDays(1).toString());
        }
        assertThat(messages()).anyMatch(message -> message.contains("4. umowa"));
    }

    @Test
    void thirtyThreeMonthsLimitSkipsExemptContracts() {
        contract(ContractKind.EMPLOYMENT, EmploymentType.FIXED, "2023-01-01", "2026-12-31");
        assertThat(messages()).anyMatch(message -> message.contains("ponad limit 33"));

        contracts.clear();
        StaffContract exempt = new StaffContract(null, null);
        exempt.update(ContractKind.EMPLOYMENT, EmploymentType.FIXED, LocalDate.parse("2023-01-01"),
                LocalDate.parse("2026-12-31"), 1, 1, true);
        contracts.add(exempt);
        assertThat(messages()).noneMatch(message -> message.contains("ponad limit 33"));
    }

    @Test
    void trialOverThreeMonthsAndRepeatedTrial() {
        contract(ContractKind.EMPLOYMENT, EmploymentType.TRIAL, "2026-01-01", "2026-04-15");
        assertThat(messages()).anyMatch(message -> message.contains("ponad 3 miesiące"));
        contract(ContractKind.EMPLOYMENT, EmploymentType.TRIAL, "2026-05-01", "2026-05-31");
        assertThat(messages()).anyMatch(message -> message.contains("innym rodzaju pracy"));
    }

    @Test
    void exactlyThreeMonthsTrialIsFine() {
        contract(ContractKind.EMPLOYMENT, EmploymentType.TRIAL, "2026-01-01", "2026-03-31");
        assertThat(messages()).noneMatch(message -> message.contains("ponad 3 miesiące"));
    }

    @Test
    void disabledEmployeeHoursAreLimitedUnlessDoctorAgrees() {
        employment("2025-01-01", null, 1, 1);
        shift("06:00", "14:00");
        assertThat(messages()).noneMatch(message -> message.contains("przekraczają"));

        disability(DisabilityDegree.MODERATE, "2025-01-01", null, false);
        assertThat(messages()).anyMatch(message -> message.contains("przekraczają 7 godzin"));

        disabilities.clear();
        disability(DisabilityDegree.MODERATE, "2025-01-01", null, true);
        assertThat(messages()).noneMatch(message -> message.contains("przekraczają"));
    }

    @Test
    void lightDegreeKeepsEightHoursButNoNightWork() {
        employment("2025-01-01", null, 1, 1);
        disability(DisabilityDegree.LIGHT, "2025-01-01", null, false);
        shift("06:00", "14:00");
        assertThat(messages()).noneMatch(message -> message.contains("przekraczają"));

        shift("22:00", "05:00");
        assertThat(messages()).anyMatch(message -> message.contains("porze nocnej"));
    }

    @Test
    void fixedHoursOnCivilContractAreNotFlagged() {
        contract(ContractKind.B2B, null, "2026-01-01", null);
        shift("06:00", "14:00");
        assertThat(messages()).noneMatch(message -> message.contains("cechy stosunku pracy"));
    }

    @Test
    void expiredExamForbidsWork() {
        employment("2026-01-01", null, 1, 1);
        profile = new StaffProfile(UUID.randomUUID());
        profile.update(null, null, null, false, LocalDate.parse("2026-01-01"), LocalDate.parse("2030-01-01"), null, null);
        assertThat(StaffCompliance.check(file(), TODAY)).extracting(Warning::severity)
                .contains(StaffCompliance.Severity.ERROR);
        assertThat(messages()).anyMatch(message -> message.contains("nie wolno dopuścić"));
    }

    @Test
    void undocumentedNewPeriodsShowDeadlineForEmployeesOfMayTwentySix() {
        employment("2025-01-01", null, 1, 1);
        periods.add(period(WorkPeriodKind.BUSINESS, "2015-01-01", "2024-12-31", false));
        assertThat(messages()).anyMatch(message -> message.contains("01.05.2028"));
    }

    @Test
    void noContractIsFirstProblem() {
        assertThat(messages().getFirst()).isEqualTo("Brak obowiązującej umowy");
    }

    private LeaveYear year(int year) {
        return LeaveCalculator.forYear(file(), year, TODAY);
    }

    private List<String> messages() {
        return StaffCompliance.check(file(), TODAY).stream().map(Warning::message).toList();
    }

    private StaffFile file() {
        return new StaffFile(profile, contracts, changes, periods, disabilities, leaves, opening, shifts);
    }

    private void employment(String from, String to, int numerator, int denominator) {
        StaffContract contract = new StaffContract(null, null);
        contract.update(ContractKind.EMPLOYMENT, EmploymentType.INDEFINITE, LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to), numerator, denominator, false);
        contracts.add(contract);
    }

    private void contract(ContractKind kind, EmploymentType type, String from, String to) {
        StaffContract contract = new StaffContract(null, null);
        contract.update(kind, type, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), 1, 1, false);
        contracts.add(contract);
    }

    private static StaffWorkPeriod period(WorkPeriodKind kind, String from, String to, boolean documented) {
        StaffWorkPeriod period = new StaffWorkPeriod(null, null);
        period.update(kind, null, LocalDate.parse(from), LocalDate.parse(to), documented);
        return period;
    }

    private void disability(DisabilityDegree degree, String from, String to, boolean consent) {
        StaffDisability disability = new StaffDisability(null, null);
        disability.update(degree, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), consent);
        disabilities.add(disability);
    }

    private void leave(LeaveKind kind, String from, int days) {
        StaffLeave leave = new StaffLeave(null, null);
        leave.update(kind, LocalDate.parse(from), LocalDate.parse(from).plusDays(days), days);
        leaves.add(leave);
    }

    private void education(EducationLevel level, String end) {
        profile = new StaffProfile(UUID.randomUUID());
        profile.update(null, level, end == null ? null : LocalDate.parse(end), false, null, null, null, null);
    }

    private void profile(String flag) {
        profile = new StaffProfile(UUID.randomUUID());
        profile.update(null, null, null, "firstJob".equals(flag), null, null, null, null);
    }

    private void shift(String start, String end) {
        shifts.add(StaffShift.ofEmployee(null, new StaffShift.Hours(java.time.DayOfWeek.MONDAY,
                java.time.LocalTime.parse(start), java.time.LocalTime.parse(end)), null));
    }
}
