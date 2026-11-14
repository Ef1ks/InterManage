package com.intermanage.backend.staff.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.staff.component.LeaveCalculator.LeaveYear;
import com.intermanage.backend.staff.component.Seniority.Length;
import com.intermanage.backend.staff.component.StaffCompliance.Warning;
import com.intermanage.backend.staff.entity.ContractKind;
import com.intermanage.backend.staff.entity.DisabilityDegree;
import com.intermanage.backend.staff.entity.EducationLevel;
import com.intermanage.backend.staff.entity.EmploymentType;
import com.intermanage.backend.staff.entity.LeaveKind;
import com.intermanage.backend.staff.entity.WorkPeriodKind;

/** Profil pracownika w Kadrach. {@code rights}: uprawnienia z ustawy o rehabilitacji wynikające z orzeczenia. */
public record StaffRecordResponse(Person person, Profile profile, List<Contract> contracts,
        List<WorkPeriod> workPeriods, List<Disability> disabilities, List<Leave> leaves, Opening opening,
        SeniorityInfo seniority, LeaveYear leaveYear, B2bLeave b2bLeave, List<Warning> warnings,
        List<String> rights) {

    /**
     * Płatna przerwa B2B w roku: dni z umowy B2B (trwającej dziś, a bez niej ostatniej w tym roku), wykorzystane wpisy
     * {@code B2B_PAID} i ile zostało. Puste, gdy w roku nie ma umowy B2B z płatnymi dniami.
     */
    public record B2bLeave(int year, int days, int usedDays, int remainingDays) {
    }

    /** {@code rcpNumber}: numer karty RCP nadany w aplikacji, do wpisania w Symfonii. */
    public record Person(UUID employeeId, String displayName, String username, String email, String storeNumber,
            String storeName, String departmentName, String positionName, String roleName, Instant endedAt,
            Integer rcpNumber) {
    }

    /** Pola podatkowe ({@code pit2} … {@code voluntarySickness}) służą szacunkowi wypłaty netto w grafiku. */
    public record Profile(LocalDate birthDate, EducationLevel educationLevel, LocalDate educationEndDate,
            boolean firstJob, LocalDate medicalExamUntil, LocalDate bhpTrainingUntil, LocalDate sanitaryUntil,
            boolean pit2, boolean higherCosts, boolean youthReliefWaived, boolean student, boolean voluntarySickness) {
    }

    /** {@code fteNumerator}: etat z chwili zawarcia; późniejsze są w {@code changes}, od najwcześniejszego. */
    public record Contract(UUID id, ContractKind kind, EmploymentType employmentType, LocalDate startDate,
            LocalDate endDate, int fteNumerator, int fteDenominator, boolean fixedTermExempt, Integer paidLeaveDays,
            List<ContractChange> changes) {
    }

    public record ContractChange(UUID id, LocalDate validFrom, int fteNumerator, int fteDenominator) {
    }

    public record WorkPeriod(UUID id, WorkPeriodKind kind, String employer, LocalDate startDate, LocalDate endDate,
            boolean documented) {
    }

    public record Disability(UUID id, DisabilityDegree degree, LocalDate startDate, LocalDate endDate,
            boolean doctorConsent) {
    }

    public record Leave(UUID id, LeaveKind kind, LocalDate startDate, LocalDate endDate, int days) {
    }

    public record Opening(int year, int days) {
    }

    /** {@code higherLeaveFrom}: dzień 10 lat stażu urlopowego; pusty bez trwającej umowy o pracę. */
    public record SeniorityInfo(Length work, Length inCompany, Length forLeave, LocalDate higherLeaveFrom) {
    }
}
