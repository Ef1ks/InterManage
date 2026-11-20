package com.intermanage.backend.task.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Pozycja zadania: artykuł z danymi z chwili zlecenia, do odhaczenia przez wykonawcę. */
@Entity
@Table(name = "store_task_items")
public class StoreTaskItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private StoreTask task;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(updatable = false)
    private String itm8;

    @Column(updatable = false)
    private String ean;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private String details;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_by")
    private AppUser checkedBy;

    @Column(name = "checked_at")
    private Instant checkedAt;

    private String note;

    protected StoreTaskItem() {
    }

    StoreTaskItem(StoreTask task, int position, String itm8, String ean, String name, String details) {
        this.task = task;
        this.position = position;
        this.itm8 = itm8;
        this.ean = ean;
        this.name = name;
        this.details = details;
    }

    public void check(AppUser user, String note) {
        checkedBy = user;
        checkedAt = Instant.now();
        this.note = note;
    }

    public void uncheck() {
        checkedBy = null;
        checkedAt = null;
        note = null;
    }

    public boolean isChecked() {
        return checkedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public StoreTask getTask() {
        return task;
    }

    public int getPosition() {
        return position;
    }

    public String getItm8() {
        return itm8;
    }

    public String getEan() {
        return ean;
    }

    public String getName() {
        return name;
    }

    public String getDetails() {
        return details;
    }

    public AppUser getCheckedBy() {
        return checkedBy;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public String getNote() {
        return note;
    }
}
