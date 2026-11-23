package com.intermanage.backend.task.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Zadanie zlecone w sklepie: tytuł, opis, termin i wykonawca, opcjonalnie pozycje (artykuły z danymi z chwili
 * zlecenia) i widok raportu, z którego powstało. Wykonawca oznacza wykonanie, zlecający przyjmuje albo odsyła.
 */
@Entity
@Table(name = "store_tasks")
public class StoreTask {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.OPEN;

    /** Wykonawca; przy zadaniu dla całego sklepu ({@link #forStore}) pusty. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private AppUser assignee;

    /** Zadanie dla całego sklepu: widzi je i wykonuje ktokolwiek z dostępem do sklepu. */
    @Column(name = "for_store", nullable = false)
    private boolean forStore;

    /** Kopie jednego zlecenia kilku osobom mają ten sam identyfikator (pojedyncze zadanie: pusty). */
    @Column(name = "group_id", updatable = false)
    private UUID groupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "source_kind", updatable = false)
    private String sourceKind;

    @Column(name = "source_label", updatable = false)
    private String sourceLabel;

    @Column(name = "source_url", updatable = false)
    private String sourceUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "done_by")
    private AppUser doneBy;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "done_comment")
    private String doneComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private AppUser acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "return_comment")
    private String returnComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL)
    @OrderBy("position")
    private List<StoreTaskItem> items = new ArrayList<>();

    protected StoreTask() {
    }

    public StoreTask(Store store, AppUser createdBy, String sourceKind, String sourceLabel, String sourceUrl,
            UUID groupId) {
        this.store = store;
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.sourceKind = sourceKind;
        this.sourceLabel = sourceLabel;
        this.sourceUrl = sourceUrl;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** {@code assignee} pusty = zadanie dla całego sklepu. */
    public void describe(String title, String description, LocalDate dueDate, TaskPriority priority,
            AppUser assignee) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = priority;
        this.assignee = assignee;
        this.forStore = assignee == null;
    }

    public StoreTaskItem addItem(String itm8, String ean, String name, String details) {
        StoreTaskItem item = new StoreTaskItem(this, items.size(), itm8, ean, name, details);
        items.add(item);
        return item;
    }

    public boolean allItemsChecked() {
        return !items.isEmpty() && items.stream().allMatch(StoreTaskItem::isChecked);
    }

    public void markDone(AppUser user, String comment) {
        status = TaskStatus.DONE;
        doneBy = user;
        doneAt = Instant.now();
        doneComment = comment;
    }

    public void reopen() {
        status = TaskStatus.OPEN;
        doneBy = null;
        doneAt = null;
        doneComment = null;
    }

    public void accept(AppUser user) {
        status = TaskStatus.ACCEPTED;
        acceptedBy = user;
        acceptedAt = Instant.now();
    }

    public void sendBack(String comment) {
        reopen();
        acceptedBy = null;
        acceptedAt = null;
        returnComment = comment;
    }

    public void markDeleted(AppUser user) {
        deletedAt = Instant.now();
        deletedBy = user;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public AppUser getAssignee() {
        return assignee;
    }

    public boolean isForStore() {
        return forStore;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public AppUser getDoneBy() {
        return doneBy;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public String getDoneComment() {
        return doneComment;
    }

    public AppUser getAcceptedBy() {
        return acceptedBy;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public String getReturnComment() {
        return returnComment;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public List<StoreTaskItem> getItems() {
        return items;
    }
}
