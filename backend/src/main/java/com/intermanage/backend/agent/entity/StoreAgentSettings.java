package com.intermanage.backend.agent.entity;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Czy sklep pracuje z folderami przez agenta, czy jak dotąd przez przeglądarkę. Jeden wiersz na sklep, nadpisywany
 * (to jedno ustawienie); bez wiersza przeglądarka.
 */
@Entity
@Table(name = "store_agent_settings")
public class StoreAgentSettings {

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false)
    private boolean enabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreAgentSettings() {
    }

    public StoreAgentSettings(UUID storeId) {
        this.storeId = storeId;
    }

    public void update(boolean enabled, AppUser by) {
        this.enabled = enabled;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public UUID getStoreId() {
        return storeId;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
