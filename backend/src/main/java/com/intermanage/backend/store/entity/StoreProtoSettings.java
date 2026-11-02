package com.intermanage.backend.store.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

/**
 * Pliki PROTO sklepu: folder, do którego program sklepu wczytuje pliki (podpowiedź przy wyborze folderu w przeglądarce,
 * wspólna dla wszystkich plików PROTO), i protokoły ukryte w oknie generowania. Do tego folder pliku nowych i
 * zmienionych artykułów (CADBEZP) z wprowadzania danych, też tylko jako podpowiedź.
 */
@Entity
@Table(name = "store_proto_settings")
public class StoreProtoSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(name = "folder_path")
    private String folderPath;

    @Column(name = "product_file_folder")
    private String productFileFolder;

    /** Nazwy stałych {@link ProtocolKind}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "hidden_protocols", nullable = false, columnDefinition = "text[]")
    private List<String> hiddenProtocols = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreProtoSettings() {
    }

    public StoreProtoSettings(Store store) {
        this.store = store;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    /** Zmienia ustawienia; zwraca, czy coś się zmieniło (bez zmian nie nadpisujemy autora). */
    public boolean update(String folderPath, String productFileFolder, List<ProtocolKind> hidden, AppUser by) {
        List<String> names = hidden.stream().distinct().sorted().map(Enum::name).toList();
        if (Objects.equals(this.folderPath, folderPath) && Objects.equals(this.productFileFolder, productFileFolder)
                && names.equals(hiddenProtocols)) {
            return false;
        }
        this.folderPath = folderPath;
        this.productFileFolder = productFileFolder;
        this.hiddenProtocols = new ArrayList<>(names);
        this.updatedBy = by;
        return true;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public String getProductFileFolder() {
        return productFileFolder;
    }

    public List<ProtocolKind> getHiddenProtocols() {
        return hiddenProtocols.stream().map(ProtocolKind::valueOf).toList();
    }

    public boolean isHidden(ProtocolKind kind) {
        return hiddenProtocols.contains(kind.name());
    }

    public AppUser getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
