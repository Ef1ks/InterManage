package com.intermanage.backend.mea.entity;

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

/**
 * Wgrany opis gazetki z centrali (V150), wspólny dla sklepów. Ponowne wgranie gazetki o tym samym roku i numerze daje
 * poprzedniemu wpisowi znacznik {@code replacedAt}.
 */
@Entity
@Table(name = "mea_leaflet_imports")
public class MeaLeafletImport {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private short year;

    @Column(nullable = false, updatable = false)
    private short week;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    /** Ścieżka pliku w portalu ({@code /api/cad/}); puste przy pliku wgranym ręcznie. */
    @Column(name = "source_path", updatable = false)
    private String sourcePath;

    /** Data zmiany pliku w portalu, tak jak ją podał (tekst do porównania). */
    @Column(name = "source_updated_at", updatable = false)
    private String sourceUpdatedAt;

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

    @OneToMany(mappedBy = "leafletImport", cascade = CascadeType.PERSIST)
    @OrderBy("lp")
    private List<MeaLeafletLine> lines = new ArrayList<>();

    protected MeaLeafletImport() {
    }

    public MeaLeafletImport(int year, int week, String fileName, AppUser by) {
        this.year = (short) year;
        this.week = (short) week;
        this.fileName = fileName.length() <= 300 ? fileName : fileName.substring(0, 300);
        this.uploadedBy = by;
    }

    /** Opis pobrany z portalu: bez osoby, ze ścieżką i datą zmiany pliku w portalu. */
    public static MeaLeafletImport fromPortal(int year, int week, String fileName, String sourcePath,
            String sourceUpdatedAt) {
        MeaLeafletImport leaflet = new MeaLeafletImport(year, week, fileName, null);
        leaflet.sourcePath = sourcePath;
        leaflet.sourceUpdatedAt = sourceUpdatedAt;
        return leaflet;
    }

    public boolean isFromPortal() {
        return sourcePath != null;
    }

    @PrePersist
    void onCreate() {
        uploadedAt = Instant.now();
    }

    public void addLine(MeaLeafletLine line) {
        line.attach(this);
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

    public List<MeaLeafletLine> getLines() {
        return lines;
    }
}
