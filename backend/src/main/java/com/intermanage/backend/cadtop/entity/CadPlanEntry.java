package com.intermanage.backend.cadtop.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Wejście numeru cadencier z planu; {@code nrCad} pusty = „zmiany ręczne”. */
@Entity
@Immutable
@Table(name = "cad_plan_entries")
public class CadPlanEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private CadPlan plan;

    @Column(nullable = false)
    private int lp;

    @Column(name = "nr_cad")
    private String nrCad;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    private String comment;

    protected CadPlanEntry() {
    }

    public CadPlanEntry(int lp, String nrCad, LocalDate startsOn, LocalDate endsOn, String comment) {
        this.lp = lp;
        this.nrCad = nrCad;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.comment = comment;
    }

    void attach(CadPlan plan) {
        this.plan = plan;
    }

    public String getNrCad() {
        return nrCad;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public String getComment() {
        return comment;
    }
}
