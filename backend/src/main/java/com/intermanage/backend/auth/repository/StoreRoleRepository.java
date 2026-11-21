package com.intermanage.backend.auth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.StoreRole;

public interface StoreRoleRepository extends JpaRepository<StoreRole, UUID> {

    /** Rangi sklepu od najwyższej, rangi poza hierarchią na końcu. */
    @EntityGraph(attributePaths = "permissions")
    @Query("select r from StoreRole r where r.store.id = :storeId and r.deletedAt is null"
            + " order by r.admin desc, r.outsideHierarchy, r.position, lower(r.name)")
    List<StoreRole> findActive(UUID storeId);

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from StoreRole r where r.store.id = :storeId and r.id = :id")
    Optional<StoreRole> findInStore(UUID storeId, UUID id);

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from StoreRole r where r.store.id = :storeId and r.key = :key and r.deletedAt is null")
    Optional<StoreRole> findActiveByKey(UUID storeId, String key);

    @Query("select r from StoreRole r join fetch r.store where r.key = :key and r.deletedAt is null"
            + " and r.store.id in :storeIds")
    List<StoreRole> findActiveByKeyInStores(String key, Collection<UUID> storeIds);

    @Query("select count(r) > 0 from StoreRole r where r.store.id = :storeId")
    boolean existsInStore(UUID storeId);

    @Query("select count(r) > 0 from StoreRole r where r.store.id = :storeId and r.deletedAt is null"
            + " and lower(r.name) = lower(:name) and (:exceptId is null or r.id <> :exceptId)")
    boolean nameTaken(UUID storeId, String name, UUID exceptId);
}
