package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Dane kadrowe pracownika: tyle, ile potrzeba do stażu, urlopu i terminów. Bez adresu i PESEL-u (kolumny
 * {@code pesel_*} z V103 nie są już używane: pracownika w Symfonii wskazuje numer karty RCP).
 */
@Entity
@Table(name = "staff_profiles")
public class StaffProfile {

    @Id
    @Column(name = "employee_id")
    private UUID employeeId;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level", length = 40)
    private EducationLevel educationLevel;

    @Column(name = "education_end_date")
    private LocalDate educationEndDate;

    /** Pierwsza praca w życiu: w pierwszym roku urlop rośnie co miesiąc (art. 153 par. 1 KP). */
    @Column(name = "first_job", nullable = false)
    private boolean firstJob;

    @Column(name = "medical_exam_until")
    private LocalDate medicalExamUntil;

    @Column(name = "bhp_training_until")
    private LocalDate bhpTrainingUntil;

    @Column(name = "sanitary_until")
    private LocalDate sanitaryUntil;

    @Column(name = "pit2", nullable = false)
    private boolean pit2 = true;

    @Column(name = "higher_costs", nullable = false)
    private boolean higherCosts;

    @Column(name = "youth_relief_waived", nullable = false)
    private boolean youthReliefWaived;

    @Column(name = "student", nullable = false)
    private boolean student;

    @Column(name = "voluntary_sickness", nullable = false)
    private boolean voluntarySickness;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StaffProfile() {
    }

    public StaffProfile(UUID employeeId) {
        this.employeeId = employeeId;
    }

    public void update(LocalDate birthDate, EducationLevel educationLevel, LocalDate educationEndDate, boolean firstJob,
            LocalDate medicalExamUntil, LocalDate bhpTrainingUntil, LocalDate sanitaryUntil, AppUser by) {
        this.birthDate = birthDate;
        this.educationLevel = educationLevel;
        this.educationEndDate = educationEndDate;
        this.firstJob = firstJob;
        this.medicalExamUntil = medicalExamUntil;
        this.bhpTrainingUntil = bhpTrainingUntil;
        this.sanitaryUntil = sanitaryUntil;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    /** Ustawienia, z których grafik szacuje wypłatę netto. */
    public void updateTax(boolean pit2, boolean higherCosts, boolean youthReliefWaived, boolean student,
            boolean voluntarySickness) {
        this.pit2 = pit2;
        this.higherCosts = higherCosts;
        this.youthReliefWaived = youthReliefWaived;
        this.student = student;
        this.voluntarySickness = voluntarySickness;
    }

    public boolean isPit2() {
        return pit2;
    }

    public boolean isHigherCosts() {
        return higherCosts;
    }

    public boolean isYouthReliefWaived() {
        return youthReliefWaived;
    }

    public boolean isStudent() {
        return student;
    }

    public boolean isVoluntarySickness() {
        return voluntarySickness;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public EducationLevel getEducationLevel() {
        return educationLevel;
    }

    public LocalDate getEducationEndDate() {
        return educationEndDate;
    }

    public boolean isFirstJob() {
        return firstJob;
    }

    public LocalDate getMedicalExamUntil() {
        return medicalExamUntil;
    }

    public LocalDate getBhpTrainingUntil() {
        return bhpTrainingUntil;
    }

    public LocalDate getSanitaryUntil() {
        return sanitaryUntil;
    }
}
