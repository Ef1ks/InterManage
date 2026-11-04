package com.intermanage.backend.leaflet.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Wgrana gazetka (opis w V75__promo_leaflets.sql). Ponowne wgranie gazetki o tym samym roku i tygodniu daje
 * poprzedniemu wpisowi znacznik {@code replacedAt}.
 */
@Entity
@Table(name = "promo_leaflets")
public class PromoLeaflet {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, updatable = false)
    private short year;

    @Column(nullable = false, updatable = false)
    private short week;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", updatable = false)
    private AppUser uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private AppUser replacedBy;

    @Column(name = "replaced_at")
    private Instant replacedAt;

    @OneToMany(mappedBy = "leaflet", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<PromoLeafletLine> lines = new ArrayList<>();

    protected PromoLeaflet() {
    }

    public PromoLeaflet(Store store, int year, int week, String fileName, AppUser by) {
        this.store = store;
        this.year = (short) year;
        this.week = (short) week;
        this.fileName = fileName;
        this.uploadedBy = by;
    }

    @PrePersist
    void onCreate() {
        uploadedAt = Instant.now();
    }

    public void addLine(PromoLeafletLine line) {
        lines.add(line);
        lineCount = lines.size();
    }

    public void replace(AppUser by) {
        replacedAt = Instant.now();
        replacedBy = by;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public int getYear() {
        return year;
    }

    public int getWeek() {
        return week;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineCount() {
        return lineCount;
    }

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public List<PromoLeafletLine> getLines() {
        return lines;
    }
}
