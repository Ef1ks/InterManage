package com.intermanage.backend.agent.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.agent.entity.AgentMacroJob;

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

    Optional<AgentMacroJob> findByIdAndStoreId(UUID id, UUID storeId);
}
