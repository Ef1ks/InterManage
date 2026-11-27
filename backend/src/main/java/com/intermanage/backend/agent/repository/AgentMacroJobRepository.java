package com.intermanage.backend.agent.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.agent.entity.AgentMacroJob;
import com.intermanage.backend.agent.entity.AgentMacroKind;
import com.intermanage.backend.agent.entity.AgentMacroStatus;

import jakarta.persistence.LockModeType;

public interface AgentMacroJobRepository extends JpaRepository<AgentMacroJob, UUID> {

    /** Trwające makro sklepu (najwyżej jedno), z blokadą: przydział komputera robi jedno żądanie naraz. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from AgentMacroJob j
            where j.store.id = :storeId and j.status in (
                com.intermanage.backend.agent.entity.AgentMacroStatus.OFFERED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.RUNNING)""")
    List<AgentMacroJob> findActiveForUpdate(UUID storeId);

    /** Czy w sklepie trwa makro albo skończyło się po {@code since} (zdarzenia z folderu przychodzą z opóźnieniem). */
    @Query("""
            select count(j) > 0 from AgentMacroJob j
            where j.store.id = :storeId and (j.status in (
                com.intermanage.backend.agent.entity.AgentMacroStatus.OFFERED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.RUNNING) or j.finishedAt > :since)""")
    boolean anyRunningSince(UUID storeId, Instant since);

    /** Kolejka sklepu od najstarszego zlecenia, z blokadą (przydział idzie po jednym żądaniu naraz). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from AgentMacroJob j
            where j.store.id = :storeId and j.status = com.intermanage.backend.agent.entity.AgentMacroStatus.QUEUED
            order by j.createdAt, j.id""")
    List<AgentMacroJob> findQueuedForUpdate(UUID storeId);

    /** Id zleceń w kolejce sklepu po kolei (do miejsca w kolejce). */
    @Query("""
            select j.id from AgentMacroJob j
            where j.store.id = :storeId and j.status = com.intermanage.backend.agent.entity.AgentMacroStatus.QUEUED
            order by j.createdAt, j.id""")
    List<UUID> findQueuedIds(UUID storeId);

    long countByStoreIdAndKindAndStatusAndCreatedByIsNullAndCreatedAtAfter(UUID storeId, AgentMacroKind kind,
            AgentMacroStatus status, Instant after);

    /** Makro bez treści (bywa duża), do listy ostatnich makr sklepu. */
    interface Recent {
        UUID getId();

        AgentMacroKind getKind();

        String getTitle();

        String getReference();

        AgentMacroStatus getStatus();

        UUID getAgentId();

        List<UUID> getDeclinedAgentIds();

        String getRequestedBy();

        Instant getCreatedAt();

        Instant getOfferedAt();

        Instant getStartedAt();

        Instant getFinishedAt();

        String getResultMessage();

        List<String> getResultCodes();

        int getItemCount();

        String getWaitReason();
    }

    /** Trwające i ostatnie makra sklepu, od najnowszego. */
    @Query("""
            select j.id as id, j.kind as kind, j.title as title, j.reference as reference, j.status as status,
                   j.agentId as agentId, j.declinedAgentIds as declinedAgentIds, u.displayName as requestedBy,
                   j.createdAt as createdAt, j.offeredAt as offeredAt, j.startedAt as startedAt,
                   j.finishedAt as finishedAt, j.resultMessage as resultMessage, j.resultCodes as resultCodes,
                   j.itemCount as itemCount, j.waitReason as waitReason
            from AgentMacroJob j left join j.createdBy u
            where j.store.id = :storeId and j.createdAt > :after and j.status not in (
                com.intermanage.backend.agent.entity.AgentMacroStatus.QUEUED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.OFFERED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.RUNNING)
            order by j.createdAt desc""")
    List<Recent> findRecent(UUID storeId, Instant after, Limit limit);

    /** Trwające i czekające makra sklepu, wszystkie bez względu na wiek (kolejka nie wygasa). */
    @Query("""
            select j.id as id, j.kind as kind, j.title as title, j.reference as reference, j.status as status,
                   j.agentId as agentId, j.declinedAgentIds as declinedAgentIds, u.displayName as requestedBy,
                   j.createdAt as createdAt, j.offeredAt as offeredAt, j.startedAt as startedAt,
                   j.finishedAt as finishedAt, j.resultMessage as resultMessage, j.resultCodes as resultCodes,
                   j.itemCount as itemCount, j.waitReason as waitReason
            from AgentMacroJob j left join j.createdBy u
            where j.store.id = :storeId and j.status in (
                com.intermanage.backend.agent.entity.AgentMacroStatus.QUEUED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.OFFERED,
                com.intermanage.backend.agent.entity.AgentMacroStatus.RUNNING)
            order by j.createdAt desc""")
    List<Recent> findPending(UUID storeId);

    Optional<AgentMacroJob> findByIdAndStoreId(UUID id, UUID storeId);
}
