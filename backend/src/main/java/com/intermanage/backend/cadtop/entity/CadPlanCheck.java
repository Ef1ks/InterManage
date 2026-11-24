package com.intermanage.backend.cadtop.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ostatnie sprawdzenie planu roku w portalu; wiersz nadpisywany. */
@Entity
@Table(name = "cad_plan_checks")
public class CadPlanCheck {

    @Id
    private short year;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    /** Pusty = portal oddał plan. */
    private String error;

    protected CadPlanCheck() {
    }

    public CadPlanCheck(int year) {
        this.year = (short) year;
    }

    public void record(String error) {
        this.checkedAt = Instant.now();
        this.error = error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }

    public int getYear() {
        return year;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public String getError() {
        return error;
    }
}
