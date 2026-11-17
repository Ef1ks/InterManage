package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Krok w historii wniosku (tylko dopisywany). */
@Entity
@Table(name = "staff_time_request_events")
public class StaffTimeRequestEvent {

    /** Co się stało; nazwa trafia do bazy. */
    public enum Action {
        SUBMITTED,
        MANAGER_APPROVED,
        MANAGER_REJECTED,
        HR_APPROVED,
        HR_REJECTED,
        CANCELLED,
        UNDONE
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private StaffTimeRequest request;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 20)
    private TimeRequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private TimeRequestStatus toStatus;

    @Column(name = "note", updatable = false, length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StaffTimeRequestEvent() {
    }

    public StaffTimeRequestEvent(StaffTimeRequest request, Action action, TimeRequestStatus fromStatus,
            TimeRequestStatus toStatus, String note, AppUser createdBy) {
        this.request = request;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public StaffTimeRequest getRequest() {
        return request;
    }

    public Action getAction() {
        return action;
    }

    public TimeRequestStatus getFromStatus() {
        return fromStatus;
    }

    public TimeRequestStatus getToStatus() {
        return toStatus;
    }

    public String getNote() {
        return note;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
