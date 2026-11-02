package com.intermanage.backend.basedelivery.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.basedelivery.entity.BaseDelivery;

public interface BaseDeliveryRepository extends JpaRepository<BaseDelivery, UUID> {

    Optional<BaseDelivery> findByStoreIdAndNumberAndReplacedAtIsNull(UUID storeId, String number);

    @EntityGraph(attributePaths = "uploadedBy")
    List<BaseDelivery> findTop30ByStoreIdAndReplacedAtIsNullOrderByDeliveryDateDescUploadedAtDesc(UUID storeId);

    @EntityGraph(attributePaths = { "uploadedBy", "lines" })
    Optional<BaseDelivery> findByIdAndStoreIdAndReplacedAtIsNull(UUID id, UUID storeId);

    interface MeaOccurrence {
        String getCode();

        String getNumber();

        LocalDate getDeliveryDate();
    }

    /** Kody MEA z aktywnych dokumentów sklepu, których nie potwierdzono jako wpisane, z bonami, w których są. */
    @Query("""
            select distinct l.meaCode as code, d.number as number, d.deliveryDate as deliveryDate
            from BaseDeliveryLine l join l.delivery d
            where d.store.id = :storeId and d.replacedAt is null and l.meaCode is not null
                and l.meaCode not in (select c.code from MeaCode c where c.store.id = :storeId and c.deletedAt is null)
            """)
    List<MeaOccurrence> findPendingMeaCodes(UUID storeId);
}
