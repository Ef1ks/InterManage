package com.intermanage.backend.basedelivery.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.basedelivery.entity.MeaConfirmation;

public interface MeaConfirmationRepository extends JpaRepository<MeaConfirmation, UUID> {

    @EntityGraph(attributePaths = { "confirmedBy", "deletedBy", "codes" })
    List<MeaConfirmation> findTop20ByStoreIdOrderByConfirmedAtDesc(UUID storeId);

    @EntityGraph(attributePaths = "codes")
    Optional<MeaConfirmation> findByIdAndStoreId(UUID id, UUID storeId);

    /** Kody sklepu potwierdzone jako wpisane (bez cofniętych). */
    @Query("select c.code from MeaCode c where c.store.id = :storeId and c.deletedAt is null")
    Set<String> findActiveCodes(UUID storeId);
}
