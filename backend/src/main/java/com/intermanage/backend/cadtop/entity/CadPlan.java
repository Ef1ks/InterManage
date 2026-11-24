package com.intermanage.backend.cadtop.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;

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

/** Plan cadencier roku (V186), wspólny dla sklepów; nowszy plan roku daje poprzedniemu znacznik {@code replacedAt}. */
@Entity
@Table(name = "cad_plans")
public class CadPlan {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private short year;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(nullable = false, updatable = false)
    private String sha256;

    /** Zapis skoroszytu w Excelu; pusty, gdy plik go nie podaje. */
    @Column(name = "modified_at", updatable = false)
    private Instant modifiedAt;

    @Column(name = "entry_count", nullable = false, updatable = false)
    private int entryCount;

    /** Ścieżka w portalu; pusta przy planie wgranym ręcznie. */
    @Column(name = "source_path", updatable = false)
    private String sourcePath;

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

    @OneToMany(mappedBy = "plan", cascade = CascadeType.PERSIST)
    @OrderBy("lp")
    private List<CadPlanEntry> entries = new ArrayList<>();

    protected CadPlan() {
    }

    public CadPlan(int year, String fileName, String sha256, Instant modifiedAt, String sourcePath, AppUser by) {
        this.year = (short) year;
        this.fileName = fileName.length() <= 300 ? fileName : fileName.substring(0, 300);
        this.sha256 = sha256;
        this.modifiedAt = modifiedAt;
        this.sourcePath = sourcePath;
        this.uploadedBy = by;
    }

    @PrePersist
    void onCreate() {
        uploadedAt = Instant.now();
    }

    public void addEntry(CadPlanEntry entry) {
        entry.attach(this);
        entries.add(entry);
        entryCount = entries.size();
    }

    public void replace(AppUser by) {
        replacedAt = Instant.now();
        replacedBy = by;
    }

    /** Ten plan jest starszy niż {@code other} (oba znają chwilę zapisu). */
    public boolean isOlderThan(CadPlan other) {
        return modifiedAt != null && other.modifiedAt != null && modifiedAt.isBefore(other.modifiedAt);
    }

    public boolean isFromPortal() {
        return sourcePath != null;
    }

    public UUID getId() {
        return id;
    }

    public int getYear() {
        return year;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getModifiedAt() {
        return modifiedAt;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public List<CadPlanEntry> getEntries() {
        return entries;
    }
}
