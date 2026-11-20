package com.intermanage.backend.task.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
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
import jakarta.persistence.Table;

/** Wpis w historii zadania (tylko dopisywany). */
@Entity
@Immutable
@Table(name = "store_task_events")
public class StoreTaskEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private StoreTask task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskEventKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false)
    private Instant at;

    private String comment;

    protected StoreTaskEvent() {
    }

    public StoreTaskEvent(StoreTask task, TaskEventKind kind, AppUser user, String comment) {
        this.task = task;
        this.kind = kind;
        this.user = user;
        this.comment = comment;
        this.at = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public TaskEventKind getKind() {
        return kind;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getAt() {
        return at;
    }

    public String getComment() {
        return comment;
    }
}
