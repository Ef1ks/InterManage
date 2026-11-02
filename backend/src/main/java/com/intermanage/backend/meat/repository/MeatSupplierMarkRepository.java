package com.intermanage.backend.meat.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.meat.entity.MeatSupplierMark;
import com.intermanage.backend.meat.entity.MeatSupplierMarkKind;

public interface MeatSupplierMarkRepository extends JpaRepository<MeatSupplierMark, UUID> {

    @Query("""
            select m from MeatSupplierMark m join fetch m.supplier
            where m.storeId = :storeId and m.kind = :kind and m.value = :value and m.deletedAt is null""")
    Optional<MeatSupplierMark> findActive(UUID storeId, MeatSupplierMarkKind kind, String value);
}
