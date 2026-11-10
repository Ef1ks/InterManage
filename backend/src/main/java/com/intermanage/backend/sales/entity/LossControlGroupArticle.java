package com.intermanage.backend.sales.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Artykuł (ITM8) podpięty do dostawcy do kontroli strat; odpięcie to znacznik. */
@Entity
@Table(name = "loss_control_group_articles")
public class LossControlGroupArticle {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private LossControlGroup group;

    @Column(nullable = false, updatable = false)
    private String itm8;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by", updatable = false)
    private AppUser addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "removed_by")
    private AppUser removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    protected LossControlGroupArticle() {
    }

    public LossControlGroupArticle(LossControlGroup group, String itm8, AppUser addedBy) {
        this.group = group;
        this.itm8 = itm8;
        this.addedBy = addedBy;
    }

    @PrePersist
    void onCreate() {
        addedAt = Instant.now();
    }

    public void remove(AppUser user) {
        removedAt = Instant.now();
        removedBy = user;
    }

    public UUID getId() {
        return id;
    }

    public LossControlGroup getGroup() {
        return group;
    }

    public String getItm8() {
        return itm8;
    }
}
