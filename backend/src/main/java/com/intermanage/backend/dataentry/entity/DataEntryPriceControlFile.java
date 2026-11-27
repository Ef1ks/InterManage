package com.intermanage.backend.dataentry.entity;

import java.time.Duration;
import java.time.Instant;
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
import jakarta.persistence.Table;

/**
 * Plik CTRLPRIX (zmiany cen sprzedaży z tabeli) zapisany przy pliku XML faktury (opis w V65). Listy czytają opis bez
 * treści ({@code DataEntryPriceControlFileRepository.Summary}).
 */
@Entity
@Table(name = "data_entry_price_control_files")
public class DataEntryPriceControlFile {

    /** Tyle najwyżej zmiana cen czeka na komputer sklepu (noc, niedziela); starszą ktoś wprowadza ręcznie. */
    public static final Duration MACRO_WAIT = Duration.ofHours(24);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "supplier_code", length = 10, updatable = false)
    private String supplierCode;

    @Column(name = "supplier_keyword", nullable = false, length = 30, updatable = false)
    private String supplierKeyword;

    @Column(name = "supplier_name", updatable = false)
    private String supplierName;

    @Column(name = "invoice_number", updatable = false)
    private String invoiceNumber;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Program sklepu wczytał plik (końcówka .TraiteN w folderze) albo ktoś oznaczył go ręcznie. */
    @Column(name = "loaded_at")
    private Instant loadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loaded_by")
    private AppUser loadedBy;

    @Column(name = "loaded_manually", nullable = false)
    private boolean loadedManually;

    /** N z końcówki .TraiteN: ile razy program sklepu przetworzył plik; puste przy ręcznym oznaczeniu. */
    @Column(name = "processed_count")
    private Integer processedCount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    /** Plik pojawił się w folderze sklepu: zmianę wprowadzi makro (do tego czasu {@code macroJobId} jest puste). */
    @Column(name = "macro_requested_at")
    private Instant macroRequestedAt;

    @Column(name = "macro_job_id")
    private UUID macroJobId;

    @Column(name = "macro_retry_at")
    private Instant macroRetryAt;

    protected DataEntryPriceControlFile() {
    }

    public DataEntryPriceControlFile(UUID storeId, UUID supplierId, String supplierCode, String supplierKeyword,
            String supplierName, String invoiceNumber, String fileName, int lineCount, byte[] content,
            AppUser createdBy) {
        this.storeId = storeId;
        this.supplierId = supplierId;
        this.supplierCode = supplierCode;
        this.supplierKeyword = supplierKeyword;
        this.supplierName = supplierName;
        this.invoiceNumber = invoiceNumber;
        this.fileName = fileName;
        this.lineCount = lineCount;
        this.content = content;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * Plik widziany w folderze z końcówką .TraiteN. Niewgrany staje się wgrany; wgrany dostaje tylko wyższą liczbę
     * przetworzeń (program wczytał go ponownie).
     *
     * <p>Wyjątek: zmiana czekająca na makro, którą program wczytał w trakcie makra sklepu ({@code duringMacro}).
     * Trwające makro zmian cen otwiera Pobranie danych, a Mercalys wczytuje wtedy do historii każdy nowy plik z folderu
     * (.Traite1), choć cen tego pliku nikt nie porównał z MSI. Taki plik zostaje niewgrany z zapamiętaną liczbą
     * przetworzeń, więc dalej czeka na swoje makro; wgrany jest dopiero po kolejnym przetworzeniu (makro: .Traite2).
     */
    public boolean seenProcessed(int count, AppUser by, boolean duringMacro) {
        boolean waiting = macroRequestedAt != null && deletedAt == null
                && macroRequestedAt.isAfter(Instant.now().minus(MACRO_WAIT));
        if (loadedAt == null && waiting && processedCount != null && count <= processedCount) {
            return false;
        }
        if (loadedAt == null && waiting && processedCount == null && macroJobId == null && duringMacro) {
            // Program sklepu wczytał plik do historii przy okazji innego makra (Pobranie danych), ale cen nikt nie
            // porównał z MSI: zmiana dalej czeka na swoje makro, a wgrana jest dopiero po kolejnym przetworzeniu.
            processedCount = count;
            return true;
        }
        if (loadedAt == null) {
            loadedAt = Instant.now();
            loadedBy = by;
            loadedManually = false;
            processedCount = count;
            return true;
        }
        if (processedCount != null && count > processedCount) {
            processedCount = count;
            return true;
        }
        return false;
    }

    /** Ponowny zapis pod nową nazwą (z bieżącą godziną): folder sprawdza już tę nazwę. */
    public void rename(String newName) {
        fileName = newName;
        if (loadedAt == null) {
            processedCount = null; // nowa nazwa: liczba przetworzeń starej nie obowiązuje
        }
        macroRequestedAt = null;
        macroJobId = null;
        macroRetryAt = null;
    }

    /** Plik pojawił się w folderze; wczytany, usunięty albo już zlecony zostaje bez zmian. */
    public void requestMacro() {
        if (macroRequestedAt == null && loadedAt == null && deletedAt == null) {
            macroRequestedAt = Instant.now();
        }
    }

    public void assignMacro(UUID jobId) {
        macroJobId = jobId;
        macroRetryAt = null;
    }

    /** Nikt nie podjął makra („Anuluj”, ktoś pracuje): zmiana znów czeka, nie wcześniej niż {@code retryAt}. */
    public void putOffMacro(Instant retryAt) {
        if (loadedAt == null && deletedAt == null) {
            macroJobId = null;
            macroRetryAt = retryAt;
        }
    }

    public void markLoaded(AppUser by) {
        loadedAt = Instant.now();
        loadedBy = by;
        loadedManually = true;
        processedCount = null;
    }

    public void unmarkLoaded() {
        loadedAt = null;
        loadedBy = null;
        loadedManually = false;
        processedCount = null;
    }

    public void delete(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public void restore() {
        deletedAt = null;
        deletedBy = null;
    }

    public boolean isLoaded() {
        return loadedAt != null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineCount() {
        return lineCount;
    }

    public byte[] getContent() {
        return content;
    }
}
