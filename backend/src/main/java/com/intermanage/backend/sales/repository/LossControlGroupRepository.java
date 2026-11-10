package com.intermanage.backend.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.LossControlGroup;

public interface LossControlGroupRepository extends JpaRepository<LossControlGroup, UUID> {

    @Query("""
            select lossGroup from LossControlGroup lossGroup
            where lossGroup.store.id = :storeId and lossGroup.deletedAt is null
            order by lower(lossGroup.name)""")
    List<LossControlGroup> findActive(UUID storeId);

    /** Także usunięta, żeby dało się ją przywrócić. */
    @Query("select lossGroup from LossControlGroup lossGroup where lossGroup.id = :id and lossGroup.store.id = :storeId")
    Optional<LossControlGroup> findInStore(UUID storeId, UUID id);

    @Query("""
            select count(lossGroup) > 0 from LossControlGroup lossGroup
            where lossGroup.store.id = :storeId and lossGroup.deletedAt is null
            and lower(lossGroup.name) = lower(:name) and lossGroup.id <> :exceptId""")
    boolean existsActiveName(UUID storeId, String name, UUID exceptId);
}
