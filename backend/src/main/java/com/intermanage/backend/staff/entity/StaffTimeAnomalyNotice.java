package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Różnica w karcie, o której pracownik dostał powiadomienie (każda raz). */
@Entity
@Table(name = "staff_time_anomaly_notices")
public class StaffTimeAnomalyNotice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private StaffEmployee employee;

    @Column(name = "part", nullable = false, updatable = false, length = 20)
    private String part;

    @Column(name = "start_at", nullable = false, updatable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", updatable = false)
    private LocalDateTime endAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffTimeAnomalyNotice() {
    }

    public StaffTimeAnomalyNotice(StaffEmployee employee, String part, LocalDateTime startAt, LocalDateTime endAt) {
        this.employee = employee;
        this.part = part;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public String getPart() {
        return part;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }
}
