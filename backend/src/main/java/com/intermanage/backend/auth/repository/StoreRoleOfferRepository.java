package com.intermanage.backend.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.StoreRoleOffer;

public interface StoreRoleOfferRepository extends JpaRepository<StoreRoleOffer, UUID> {

    String PENDING = "o.acceptedAt is null and o.rejectedAt is null and o.cancelledAt is null";

    /** Oczekujące propozycje do sklepu albo od niego, od najnowszej. */
    @Query("select o from StoreRoleOffer o join fetch o.fromStore join fetch o.toStore left join fetch o.createdBy"
            + " where (o.toStore.id = :storeId or o.fromStore.id = :storeId) and " + PENDING
            + " order by o.createdAt desc")
    List<StoreRoleOffer> findPending(UUID storeId);

    @Query("select o from StoreRoleOffer o where o.fromStore.id = :fromStoreId and o.toStore.id = :toStoreId and "
            + PENDING)
    Optional<StoreRoleOffer> findPending(UUID fromStoreId, UUID toStoreId);
}
