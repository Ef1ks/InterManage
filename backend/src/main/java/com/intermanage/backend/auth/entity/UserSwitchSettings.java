package com.intermanage.backend.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** PIN konta do przełączania kont w przeglądarce, to, czy przejście na konto go wymaga, i automatyczne wylogowywanie. */
@Entity
@Table(name = "user_switch_settings")
public class UserSwitchSettings {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private AppUser user;

    @Column(name = "pin_hash")
    private String pinHash;

    /** Liczba cyfr PIN-u; puste przy PIN-ie sprzed V168, dopóki pierwsze udane przejście jej nie zapisze. */
    @Column(name = "pin_length")
    private Short pinLength;

    @Column(name = "pin_required", nullable = false)
    private boolean pinRequired = true;

    /** Czy konto jest wylogowywane samo (po bezczynności i po pracy). */
    @Column(name = "auto_logout", nullable = false)
    private boolean autoLogout = true;

    /** Po ilu minutach bez ruchu; puste = domyślny czas aplikacji. */
    @Column(name = "idle_lock_minutes")
    private Integer idleLockMinutes;

    /** Czy także po godzinie końca pracy. */
    @Column(name = "workday_lock", nullable = false)
    private boolean workdayLock = true;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserSwitchSettings() {
    }

    public UserSwitchSettings(AppUser user) {
        this.user = user;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getPinHash() {
        return pinHash;
    }

    public boolean hasPin() {
        return pinHash != null;
    }

    /** Nowy PIN zeruje błędne próby. */
    public void changePin(String pinHash, int pinLength) {
        this.pinHash = pinHash;
        this.pinLength = (short) pinLength;
        this.failedAttempts = 0;
    }

    public Integer getPinLength() {
        return pinLength == null ? null : pinLength.intValue();
    }

    /** Po udanym przejściu PIN-em: zapamiętuje jego długość, jeśli jej jeszcze nie znamy. */
    public void learnPinLength(int length) {
        if (pinLength == null) {
            pinLength = (short) length;
        }
    }

    public boolean isPinRequired() {
        return pinRequired;
    }

    public void setPinRequired(boolean pinRequired) {
        this.pinRequired = pinRequired;
    }

    public boolean isAutoLogout() {
        return autoLogout;
    }

    public Integer getIdleLockMinutes() {
        return idleLockMinutes;
    }

    public boolean isWorkdayLock() {
        return workdayLock;
    }

    /** Puste minuty zostawiają dotychczasowy czas. */
    public void changeAutoLogout(boolean enabled, Integer minutes, boolean workdayLock) {
        this.autoLogout = enabled;
        if (minutes != null) {
            this.idleLockMinutes = minutes;
        }
        this.workdayLock = workdayLock;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void failedAttempt() {
        failedAttempts++;
    }

    public void resetAttempts() {
        failedAttempts = 0;
    }
}
