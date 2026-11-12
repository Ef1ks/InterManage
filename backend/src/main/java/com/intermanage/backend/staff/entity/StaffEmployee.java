package com.intermanage.backend.staff.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;

import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.store.entity.Store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Pracownik firmy: konto (albo samo imię i nazwisko) i stanowisko w sklepie, w którym pracuje teraz. Może przejść do innego sklepu z tym samym
 * NIP-em ({@link StaffEmployeeMove}). Koniec pracy to znacznik, a powrót tej osoby zdejmuje go.
 */
@Entity
@Table(name = "staff_employees")
public class StaffEmployee {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    /** Konto w aplikacji; puste = pracownik bez konta (tylko imię i nazwisko w {@link #fullName}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false)
    private StaffPosition position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by")
    private AppUser endedBy;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Numer karty RCP do Symfonii: nadaje go baza z sekwencji przy zakładaniu (V112), potem się nie zmienia. */
    @Generated
    @Column(name = "rcp_number", insertable = false, updatable = false)
    private Integer rcpNumber;

    protected StaffEmployee() {
    }

    public StaffEmployee(Store store, AppUser user, StaffPosition position, AppUser createdBy) {
        this.store = store;
        this.user = user;
        this.position = position;
        this.createdBy = createdBy;
    }

    public static StaffEmployee withoutAccount(Store store, String fullName, StaffPosition position,
            AppUser createdBy) {
        StaffEmployee employee = new StaffEmployee(store, null, position, createdBy);
        employee.fullName = fullName;
        return employee;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void markEnded(AppUser by) {
        endedAt = Instant.now();
        endedBy = by;
    }

    public void restore() {
        endedAt = null;
        endedBy = null;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public AppUser getUser() {
        return user;
    }

    public boolean hasAccount() {
        return user != null;
    }

    /** Imię i nazwisko: z konta, a bez konta z wpisu pracownika. */
    public String getDisplayName() {
        return user != null ? user.getDisplayName() : fullName;
    }

    /**
     * Podpina konto pracownikowi dotąd bez konta. Od tej chwili imię i nazwisko jest w koncie, a grafik, kadry i czas pracy
     * zostają przy tym samym wpisie pracownika.
     */
    public void linkAccount(AppUser account) {
        if (user != null) {
            throw new IllegalStateException("Pracownik ma już konto");
        }
        this.user = account;
        this.fullName = null;
    }

    public void setFullName(String fullName) {
        if (user != null) {
            throw new IllegalStateException("Pracownik z kontem ma imię i nazwisko w koncie");
        }
        this.fullName = fullName;
    }

    public StaffPosition getPosition() {
        return position;
    }

    public void setPosition(StaffPosition position) {
        this.position = position;
    }

    public void moveTo(Store store, StaffPosition position) {
        this.store = store;
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Integer getRcpNumber() {
        return rcpNumber;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
