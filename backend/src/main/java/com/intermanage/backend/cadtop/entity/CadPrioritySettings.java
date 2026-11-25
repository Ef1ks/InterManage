package com.intermanage.backend.cadtop.entity;

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

/** Automatyczne poprawianie ITM8 z cadencier po aktualizacji danych (V194), osobno w każdym sklepie. */
@Entity
@Table(name = "cad_priority_settings")
public class CadPrioritySettings {

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false)
    private boolean automatic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CadPrioritySettings() {
    }

    public CadPrioritySettings(UUID storeId) {
        this.storeId = storeId;
    }

    public void update(boolean automatic, AppUser by) {
        this.automatic = automatic;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public boolean isAutomatic() {
        return automatic;
    }
}
