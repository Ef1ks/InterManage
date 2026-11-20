package com.intermanage.backend.agent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Agent sklepu: program na komputerze w sklepie, który zapisuje pliki z kolejki w folderach i pilnuje folderów.
 * W bazie tylko SHA-256 klucza. {@code lastSeenAt} i opis komputera odświeża każde żądanie agenta (zapytaniem, nie
 * przez encję), więc są tu tylko do odczytu.
 */
@Entity
@Table(name = "store_agents")
public class StoreAgent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "last_version", insertable = false, updatable = false)
    private String lastVersion;

    @Column(name = "machine_name", insertable = false, updatable = false)
    private String machineName;

    @Column(name = "machine_user", insertable = false, updatable = false)
    private String machineUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StoreAgent() {
    }

    public StoreAgent(Store store, String name, String keyHash, AppUser createdBy) {
        this.store = store;
        this.name = name;
        this.keyHash = keyHash;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
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

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLastVersion() {
        return lastVersion;
    }

    public String getMachineName() {
        return machineName;
    }

    public String getMachineUser() {
        return machineUser;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
