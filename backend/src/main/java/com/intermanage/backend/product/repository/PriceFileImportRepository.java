package com.intermanage.backend.product.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.PriceFileImport;

public interface PriceFileImportRepository extends JpaRepository<PriceFileImport, UUID> {

    @Query("select i.fileName from PriceFileImport i where i.store.id = :storeId and i.fileName in :fileNames")
    List<String> findImportedNames(UUID storeId, Collection<String> fileNames);

    @Query("select i from PriceFileImport i left join fetch i.importedBy where i.store.id = :storeId "
            + "order by i.fileTime desc, i.importedAt desc")
    List<PriceFileImport> findLatest(UUID storeId, Limit limit);

    @Query("select count(p) from Product p where p.store.id = :storeId and p.promoPrice is not null "
            + "and p.promoTo >= :today and (p.promoFrom is null or p.promoFrom <= :today)")
    long countActivePromotions(UUID storeId, LocalDate today);
}
