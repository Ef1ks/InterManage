package com.intermanage.backend.store.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.store.entity.StorePartner;

public interface StorePartnerRepository extends JpaRepository<StorePartner, UUID> {

    /** Zaproszenia i znajomości sklepu (z obu stron), bez cofniętych. */
    @Query("""
            select p from StorePartner p join fetch p.store join fetch p.partnerStore
            left join fetch p.invitedBy left join fetch p.acceptedBy
            where (p.store.id = :storeId or p.partnerStore.id = :storeId) and p.deletedAt is null
            order by p.invitedAt""")
    List<StorePartner> findActive(UUID storeId);

    /** Wpis pary sklepów bez znacznika, niezależnie od tego, który zapraszał. */
    @Query("""
            select p from StorePartner p
            where p.deletedAt is null
              and (p.store.id = :first and p.partnerStore.id = :second
                   or p.store.id = :second and p.partnerStore.id = :first)""")
    Optional<StorePartner> findActivePair(UUID first, UUID second);

    /** Czy sklepy są zaprzyjaźnione (zaproszenie zaakceptowane). */
    @Query("""
            select count(p) > 0 from StorePartner p
            where p.deletedAt is null and p.acceptedAt is not null
              and (p.store.id = :first and p.partnerStore.id = :second
                   or p.store.id = :second and p.partnerStore.id = :first)""")
    boolean arePartners(UUID first, UUID second);

    @Query("""
            select p from StorePartner p join fetch p.store join fetch p.partnerStore
            where p.id = :id and (p.store.id = :storeId or p.partnerStore.id = :storeId)""")
    Optional<StorePartner> findInStore(UUID storeId, UUID id);

    /** Ile zaproszeń czeka na odpowiedź sklepu. */
    @Query("""
            select count(p) from StorePartner p
            where p.partnerStore.id = :storeId and p.deletedAt is null and p.acceptedAt is null""")
    long countPendingReceived(UUID storeId);
}
