package com.intermanage.backend.ksef.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.ksef.entity.KsefSkippedSupplier;

public interface KsefSkippedSupplierRepository extends JpaRepository<KsefSkippedSupplier, UUID> {

    @Query("""
            select s from KsefSkippedSupplier s where s.store.id = :storeId and s.removedAt is null
            order by lower(coalesce(s.name, s.nip))""")
    List<KsefSkippedSupplier> findActive(UUID storeId);

    @Query("select s from KsefSkippedSupplier s where s.id = :id and s.store.id = :storeId")
    Optional<KsefSkippedSupplier> findInStore(UUID storeId, UUID id);

    @Query("""
            select count(s) > 0 from KsefSkippedSupplier s
            where s.store.id = :storeId and s.nip = :nip and s.removedAt is null""")
    boolean existsActive(UUID storeId, String nip);
}
