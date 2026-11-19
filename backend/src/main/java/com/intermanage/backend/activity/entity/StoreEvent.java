package com.intermanage.backend.activity.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.intermanage.backend.activity.component.StoreEventRecord;

/**
 * Wpis w dzienniku sklepu. Id i czas nadaje zdarzenie w chwili, gdy się wydarzyło ({@link StoreEventRecord}), a nie
 * zapis, więc wpis odtworzony z pliku po awarii bazy staje w swoim miejscu na liście. Wiersz się nie zmienia.
 */
@Entity
@Immutable
@Table(name = "store_events")
public class StoreEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "store_id", updatable = false)
    private UUID storeId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private StoreEventKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private StoreEventLevel level;

    @Column(updatable = false, length = 40)
    private String module;

    @Column(updatable = false, length = 10)
    private String method;

    @Column(updatable = false, length = 500)
    private String path;

    @Column(updatable = false)
    private Integer status;

    @Column(name = "duration_ms", updatable = false)
    private Integer durationMs;

    @Column(nullable = false, updatable = false, length = 500)
    private String summary;

    @Column(updatable = false, columnDefinition = "text")
    private String detail;

    protected StoreEvent() {
    }

    public static StoreEvent of(StoreEventRecord record) {
        StoreEvent event = new StoreEvent();
        event.id = record.id();
        event.occurredAt = record.occurredAt();
        event.storeId = record.storeId();
        event.userId = record.userId();
        event.kind = record.kind();
        event.level = record.level();
        event.module = record.module();
        event.method = record.method();
        event.path = record.path();
        event.status = record.status();
        event.durationMs = record.durationMs();
        event.summary = record.summary();
        event.detail = record.detail();
        return event;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Id nadaje zdarzenie, a wiersz nigdy się nie zmienia: zawsze INSERT, bez SELECT-a przed zapisem. */
    @Override
    public boolean isNew() {
        return true;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getUserId() {
        return userId;
    }

    public StoreEventKind getKind() {
        return kind;
    }

    public StoreEventLevel getLevel() {
        return level;
    }

    public String getModule() {
        return module;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Integer getStatus() {
        return status;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }
}
