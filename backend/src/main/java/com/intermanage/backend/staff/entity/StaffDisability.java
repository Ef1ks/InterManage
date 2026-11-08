package com.intermanage.backend.staff.entity;

import java.time.LocalDate;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Orzeczenie: sam stopień i okres, bez rozpoznania. {@code doctorConsent}: zgoda lekarza z art. 16 ustawy o rehabilitacji. */
@Entity
@Table(name = "staff_disabilities")
public class StaffDisability extends StaffRecordEntry {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisabilityDegree degree;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Pusta = orzeczenie na stałe. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "doctor_consent", nullable = false)
    private boolean doctorConsent;

    protected StaffDisability() {
    }

    public StaffDisability(StaffEmployee employee, AppUser createdBy) {
        super(employee, createdBy);
    }

    public void update(DisabilityDegree degree, LocalDate startDate, LocalDate endDate, boolean doctorConsent) {
        this.degree = degree;
        this.startDate = startDate;
        this.endDate = endDate;
        this.doctorConsent = doctorConsent;
    }

    public boolean activeOn(LocalDate day) {
        return !startDate.isAfter(day) && (endDate == null || !endDate.isBefore(day));
    }

    /** Znaczny i umiarkowany dają 7 godzin na dobę i dodatkowy urlop; lekki nie. */
    public boolean isSignificant() {
        return degree != DisabilityDegree.LIGHT;
    }

    public DisabilityDegree getDegree() {
        return degree;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean isDoctorConsent() {
        return doctorConsent;
    }
}
