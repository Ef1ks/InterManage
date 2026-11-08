package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeDecisionFile;

/** Podpisane polecenia i wnioski o nadgodziny; aktywny jest nieusunięty, najwyżej jeden na decyzję. */
public interface StaffTimeDecisionFileRepository extends JpaRepository<StaffTimeDecisionFile, UUID> {

    /** Opis pliku bez treści, do kart czasu pracy. */
    interface Summary {
        UUID getId();

        UUID getDecisionId();

        String getFileName();
    }

    @Query("""
            select f.id as id, f.decision.id as decisionId, f.fileName as fileName
            from StaffTimeDecisionFile f where f.decision.id in :decisionIds and f.removedAt is null""")
    List<Summary> findActiveSummaries(Collection<UUID> decisionIds);

    @Query("select f from StaffTimeDecisionFile f where f.decision.id = :decisionId and f.removedAt is null")
    Optional<StaffTimeDecisionFile> findActive(UUID decisionId);
}
