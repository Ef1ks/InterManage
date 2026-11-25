package com.intermanage.backend.agent.entity;

import java.time.Instant;
import java.util.UUID;

import com.intermanage.backend.auth.entity.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Funkcja agenta przełączona w sklepie (wiersz nadpisywany; bez wiersza funkcja jest włączona). */
@Entity
@Table(name = "store_agent_features")
public class StoreAgentFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private AgentFeature feature;

    @Column(nullable = false)
    private boolean enabled;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreAgentFeature() {
    }

    public StoreAgentFeature(UUID storeId, AgentFeature feature) {
        this.storeId = storeId;
        this.feature = feature;
    }

    public void update(boolean value, AppUser by) {
        enabled = value;
        updatedBy = by;
        updatedAt = Instant.now();
    }

    public AgentFeature getFeature() {
        return feature;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
