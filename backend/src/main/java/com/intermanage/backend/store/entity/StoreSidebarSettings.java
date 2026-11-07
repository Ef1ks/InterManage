package com.intermanage.backend.store.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Kolejność kategorii w menu bocznym sklepu: klucze grup z frontu, pusta lista = kolejność domyślna. */
@Entity
@Table(name = "store_sidebar_settings")
public class StoreSidebarSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "group_order", nullable = false, columnDefinition = "text[]")
    private List<String> groupOrder = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreSidebarSettings() {
    }

    public StoreSidebarSettings(Store store) {
        this.store = store;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void update(List<String> groupOrder, AppUser by) {
        this.groupOrder = new ArrayList<>(groupOrder);
        this.updatedBy = by;
    }

    public Store getStore() {
        return store;
    }

    public List<String> getGroupOrder() {
        return groupOrder;
    }
}
