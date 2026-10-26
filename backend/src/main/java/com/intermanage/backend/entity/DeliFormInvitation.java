package com.intermanage.backend.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Zaproszenie sklepu do formularza zamówień garmażu innego sklepu. Po akceptacji zaproszony sklep widzi formularz
 * dostawcy i może wysyłać mu zamówienia.
 */
@Entity
@Table(name = "deli_form_invitations")
public class DeliFormInvitation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "form_store_id", nullable = false, updatable = false)
    private Store formStore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private AppUser invitedBy;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responded_by")
    private AppUser respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected DeliFormInvitation() {
    }

    public DeliFormInvitation(Store formStore, Store store, AppUser invitedBy) {
        this.formStore = formStore;
        this.store = store;
        reinvite(invitedBy);
    }

    /** Ponowne zaproszenie po odrzuceniu: znowu czeka na odpowiedź. */
    public void reinvite(AppUser by) {
        status = InvitationStatus.PENDING;
        invitedBy = by;
        invitedAt = Instant.now();
        respondedBy = null;
        respondedAt = null;
    }

    public void respond(boolean accepted, AppUser by) {
        status = accepted ? InvitationStatus.ACCEPTED : InvitationStatus.DECLINED;
        respondedBy = by;
        respondedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Store getFormStore() {
        return formStore;
    }

    public Store getStore() {
        return store;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }
}
