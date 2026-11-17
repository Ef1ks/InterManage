package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeStoreRules;

public interface StaffTimeStoreRulesRepository extends JpaRepository<StaffTimeStoreRules, UUID> {

    @Query("select r from StaffTimeStoreRules r where r.store.id = :storeId and r.deletedAt is null")
    Optional<StaffTimeStoreRules> findActive(UUID storeId);

    @Query("select r from StaffTimeStoreRules r where r.store.id in :storeIds and r.deletedAt is null")
    List<StaffTimeStoreRules> findActive(Collection<UUID> storeIds);
}
