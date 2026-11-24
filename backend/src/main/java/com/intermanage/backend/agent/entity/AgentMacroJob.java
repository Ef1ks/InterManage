package com.intermanage.backend.agent.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

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
 * Makro zlecone z przeglądarki na wolnym komputerze sklepu. Treść składa serwer ({@code macro}, bez hasła Mercalys:
 * dokłada je agent). {@code agentId}: komputer, któremu zlecenie zaproponowano albo który je wykonuje;
 * {@code declinedAgentIds}: komputery, które odmówiły (pracownik anulował odliczanie albo nie odpowiedziały).
 */
@Entity
@Table(name = "agent_macro_jobs")
public class AgentMacroJob {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private AgentMacroKind kind;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String macro;

    /** Czego dotyczy makro (np. numer bonu z bazy), żeby wynik trafił we właściwe miejsce. */
    @Column(length = 60, updatable = false)
    private String reference;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentMacroStatus status;

    @Column(name = "target_agent_id", updatable = false)
    private UUID targetAgentId;

    @Column(name = "agent_id")
    private UUID agentId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "declined_agent_ids", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> declinedAgentIds = new ArrayList<>();

    @Column(name = "offered_at")
    private Instant offeredAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "result_message")
    private String resultMessage;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "result_codes", nullable = false, columnDefinition = "text[]")
    private List<String> resultCodes = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private AppUser createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private AppUser cancelledBy;

    protected AgentMacroJob() {
    }

    public AgentMacroJob(Store store, AgentMacroKind kind, String title, String macro, String reference,
            int itemCount, UUID targetAgentId, AppUser createdBy) {
        this.reference = reference;
        this.store = store;
        this.kind = kind;
        this.title = title.length() > 200 ? title.substring(0, 200) : title;
        this.macro = macro;
        this.itemCount = itemCount;
        this.targetAgentId = targetAgentId;
        this.createdBy = createdBy;
        this.status = AgentMacroStatus.NO_COMPUTER;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public void offer(UUID agent, Instant now) {
        status = AgentMacroStatus.OFFERED;
        agentId = agent;
        offeredAt = now;
    }

    /** Komputer odmówił (albo nie odpowiedział): nie dostanie już tego zlecenia. */
    public void decline(UUID agent) {
        if (!declinedAgentIds.contains(agent)) {
            List<UUID> list = new ArrayList<>(declinedAgentIds);
            list.add(agent);
            declinedAgentIds = list;
        }
    }

    public void start(Instant now) {
        status = AgentMacroStatus.RUNNING;
        startedAt = now;
    }

    public void finish(AgentMacroStatus result, String message, List<String> codes, Instant now) {
        status = result;
        resultMessage = message;
        resultCodes = new ArrayList<>(codes);
        finishedAt = now;
    }

    public void cancel(AppUser by, Instant now) {
        status = AgentMacroStatus.CANCELLED;
        cancelledBy = by;
        finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Store getStore() {
        return store;
    }

    public AgentMacroKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getMacro() {
        return macro;
    }

    public String getReference() {
        return reference;
    }

    public int getItemCount() {
        return itemCount;
    }

    public AgentMacroStatus getStatus() {
        return status;
    }

    public UUID getTargetAgentId() {
        return targetAgentId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public List<UUID> getDeclinedAgentIds() {
        return declinedAgentIds;
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public List<String> getResultCodes() {
        return resultCodes;
    }

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
