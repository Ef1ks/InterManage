package com.intermanage.backend.bakery.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.bakery.entity.BakeryCheck;
import com.intermanage.backend.bakery.entity.BakeryCheckDocument;
import com.intermanage.backend.bakery.entity.BakeryCheckInvoice;

public interface BakeryCheckRepository extends JpaRepository<BakeryCheck, UUID> {

    Optional<BakeryCheck> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("""
            select c from BakeryCheck c join fetch c.supplier left join fetch c.createdBy
            where c.storeId = :storeId and c.supplier.id = :supplierId and c.deletedAt is null
            order by c.createdAt desc""")
    List<BakeryCheck> findBySupplier(UUID storeId, UUID supplierId, Limit limit);

    @Query("""
            select d from BakeryCheckDocument d join fetch d.document
            where d.check.id in :checkIds and d.removedAt is null order by d.addedAt""")
    List<BakeryCheckDocument> findDocuments(Collection<UUID> checkIds);

    @Query("""
            select i from BakeryCheckInvoice i join fetch i.invoice
            where i.check.id in :checkIds and i.removedAt is null order by i.addedAt""")
    List<BakeryCheckInvoice> findInvoices(Collection<UUID> checkIds);

    /** Przypięcia podanych przyjęć do weryfikacji (nieusuniętych). */
    @Query("""
            select d from BakeryCheckDocument d join fetch d.check c
            where d.document.id in :documentIds and d.removedAt is null and c.deletedAt is null""")
    List<BakeryCheckDocument> findActiveByDocuments(Collection<UUID> documentIds);
}
