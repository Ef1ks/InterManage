package com.intermanage.backend.store.entity;

import java.time.Instant;
import java.util.Objects;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Jedna ścieżka sklepu na dysku sieciowym (folder raportu, folder zapisu, ścieżka zapisu makra, maska nazwy pliku…).
 * W sklepie są takie same na każdym komputerze, więc ustawia się je raz. Wiersz jest nadpisywany, pusta wartość to brak.
 */
@Entity
@Table(name = "store_file_locations")
public class StoreFileLocation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "location_key", nullable = false, updatable = false)
    private String key;

    @Column(name = "value")
    private String value;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreFileLocation() {
    }

    public StoreFileLocation(Store store, String key) {
        this.store = store;
        this.key = key;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zmienia wartość; zwraca, czy coś się zmieniło (bez zmian nie nadpisujemy autora). */
    public boolean update(String value, AppUser by) {
        if (Objects.equals(this.value, value)) return false;
        this.value = value;
        this.updatedBy = by;
        return true;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
