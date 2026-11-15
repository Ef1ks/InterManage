package com.intermanage.backend.mea.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Formularz MEA z portalu, wspólny dla wszystkich sklepów. Powstaje z listy MEA (tytuł, okresy, ścieżka pliku),
 * a pozycje dochodzą raz, po pobraniu formularza ({@link #fill}); później się nie zmienia, jak w eMEA.
 */
@Entity
@Table(name = "mea_documents")
public class MeaDocument {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String number;

    private String title;

    @Column(name = "promo_from")
    private LocalDate promoFrom;

    @Column(name = "promo_to")
    private LocalDate promoTo;

    @Column(name = "reservation_from")
    private LocalDate reservationFrom;

    @Column(name = "reservation_to")
    private LocalDate reservationTo;

    @Column(name = "dispatch_1")
    private LocalDate dispatch1;

    @Column(name = "dispatch_2")
    private LocalDate dispatch2;

    @Column(name = "dispatch_3")
    private LocalDate dispatch3;

    @Column(name = "dispatch_4")
    private LocalDate dispatch4;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @OneToMany(mappedBy = "document", cascade = CascadeType.PERSIST)
    @OrderBy("lp")
    private List<MeaDocumentLine> lines = new ArrayList<>();

    protected MeaDocument() {
    }

    public MeaDocument(String number, String filePath) {
        this.number = number;
        this.filePath = filePath;
        this.createdAt = Instant.now();
    }

    /** Dane z listy MEA; formularz pobrany wcześniej ma pierwszeństwo, więc go nie nadpisujemy. */
    public void describe(String title, LocalDate promoFrom, LocalDate promoTo, LocalDate reservationFrom,
            LocalDate reservationTo, String filePath) {
        if (fetchedAt != null) {
            return;
        }
        this.title = title;
        this.promoFrom = promoFrom;
        this.promoTo = promoTo;
        this.reservationFrom = reservationFrom;
        this.reservationTo = reservationTo;
        if (filePath != null) {
            this.filePath = filePath;
        }
    }

    /** Pobrany formularz: nagłówek z pliku i pozycje (raz). */
    public void fill(String title, LocalDate promoFrom, LocalDate promoTo, LocalDate reservationFrom,
            LocalDate reservationTo, List<LocalDate> dispatches, List<MeaDocumentLine> newLines) {
        if (fetchedAt != null) {
            throw new IllegalStateException("Formularz MEA " + number + " jest już pobrany");
        }
        if (title != null) {
            this.title = title;
        }
        this.promoFrom = promoFrom != null ? promoFrom : this.promoFrom;
        this.promoTo = promoTo != null ? promoTo : this.promoTo;
        this.reservationFrom = reservationFrom != null ? reservationFrom : this.reservationFrom;
        this.reservationTo = reservationTo != null ? reservationTo : this.reservationTo;
        this.dispatch1 = dispatches.size() > 0 ? dispatches.get(0) : null;
        this.dispatch2 = dispatches.size() > 1 ? dispatches.get(1) : null;
        this.dispatch3 = dispatches.size() > 2 ? dispatches.get(2) : null;
        this.dispatch4 = dispatches.size() > 3 ? dispatches.get(3) : null;
        newLines.forEach(line -> line.attach(this));
        this.lines.addAll(newLines);
        this.lineCount = newLines.size();
        this.fetchedAt = Instant.now();
        this.lastAttemptAt = fetchedAt;
        this.attempts++;
        this.lastError = null;
    }

    public void failed(String error) {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
        this.lastError = error;
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getPromoFrom() {
        return promoFrom;
    }

    public LocalDate getPromoTo() {
        return promoTo;
    }

    public LocalDate getReservationFrom() {
        return reservationFrom;
    }

    public LocalDate getReservationTo() {
        return reservationTo;
    }

    /** Daty rozrzutów R1-R4 po kolei, bez pustych. */
    public List<LocalDate> getDispatches() {
        return Stream.of(dispatch1, dispatch2, dispatch3, dispatch4).takeWhile(date -> date != null).toList();
    }

    public String getFilePath() {
        return filePath;
    }

    public boolean isFetched() {
        return fetchedAt != null;
    }

    public List<MeaDocumentLine> getLines() {
        return lines;
    }
}
