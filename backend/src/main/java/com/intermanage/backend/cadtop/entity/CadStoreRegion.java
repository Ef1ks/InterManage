package com.intermanage.backend.cadtop.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Rejon logistyczny sklepu (np. {@code swadzim}) z plików TOP, które pokazuje mu portal; wiersz nadpisywany. */
@Entity
@Table(name = "cad_store_regions")
public class CadStoreRegion {

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false)
    private String region;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CadStoreRegion() {
    }

    public CadStoreRegion(UUID storeId) {
        this.storeId = storeId;
    }

    public void change(String region) {
        this.region = region;
        this.updatedAt = Instant.now();
    }

    public String getRegion() {
        return region;
    }
}
