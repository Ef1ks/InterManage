package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Publikacja grafiku. Numer 1 to rozkład przekazany pracownikom pierwszy raz (art. 129 § 3 KP), kolejne to zmiany po nim.
 * Baza nie pozwala zmienić ani usunąć wersji.
 */
@Entity
@Immutable
@Table(name = "staff_schedule_versions")
public class StaffScheduleVersion {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, updatable = false)
    private StaffSchedule schedule;

    @Column(nullable = false, updatable = false)
    private int number;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by", updatable = false)
    private AppUser publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    protected StaffScheduleVersion() {
    }

    public StaffScheduleVersion(StaffSchedule schedule, int number, AppUser publishedBy) {
        this.schedule = schedule;
        this.number = number;
        this.publishedBy = publishedBy;
    }

    @PrePersist
    void onCreate() {
        publishedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public StaffSchedule getSchedule() {
        return schedule;
    }

    public int getNumber() {
        return number;
    }

    public AppUser getPublishedBy() {
        return publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
