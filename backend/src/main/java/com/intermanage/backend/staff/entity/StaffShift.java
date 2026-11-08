package com.intermanage.backend.staff.entity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Godziny pracy w danym dniu tygodnia: stanowiska (do wyboru, osobno w każdym sklepie, bo stanowisko jest wspólne dla
 * firmy) albo pracownika (jego stałe). Pracownik może mieć godziny spoza stanowiska, więc to wartości, nie odwołanie.
 * Koniec przed początkiem to zmiana przez północ. Usunięcie to znacznik.
 */
@Entity
@Table(name = "staff_shifts")
public class StaffShift {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", updatable = false)
    private StaffPosition position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", updatable = false)
    private StaffEmployee employee;

    /** Sklep godzin stanowiska; stałe godziny pracownika idą za nim, więc sklepu nie mają. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 20, updatable = false)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime start;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime end;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private AppUser deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected StaffShift() {
    }

    private StaffShift(StaffPosition position, Store store, StaffEmployee employee, Hours hours, AppUser createdBy) {
        this.position = position;
        this.store = store;
        this.employee = employee;
        this.dayOfWeek = hours.dayOfWeek();
        this.start = hours.start();
        this.end = hours.end();
        this.createdBy = createdBy;
    }

    public static StaffShift ofPosition(StaffPosition position, Store store, Hours hours, AppUser createdBy) {
        return new StaffShift(position, store, null, hours, createdBy);
    }

    public static StaffShift ofEmployee(StaffEmployee employee, Hours hours, AppUser createdBy) {
        return new StaffShift(null, null, employee, hours, createdBy);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markDeleted(AppUser by) {
        deletedAt = Instant.now();
        deletedBy = by;
    }

    public Hours hours() {
        return new Hours(dayOfWeek, start, end);
    }

    public StaffPosition getPosition() {
        return position;
    }

    public StaffEmployee getEmployee() {
        return employee;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStart() {
        return start;
    }

    public LocalTime getEnd() {
        return end;
    }

    /** Same godziny, bez właściciela: po nich porównujemy listę zapisaną z nową. */
    public record Hours(DayOfWeek dayOfWeek, LocalTime start, LocalTime end) {
    }
}
