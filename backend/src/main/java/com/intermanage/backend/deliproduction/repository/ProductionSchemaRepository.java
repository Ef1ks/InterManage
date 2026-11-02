package com.intermanage.backend.deliproduction.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliproduction.entity.ProductionSchema;

public interface ProductionSchemaRepository extends JpaRepository<ProductionSchema, UUID> {

    @Query("select s from ProductionSchema s where s.storeId = :storeId and s.deletedAt is null order by lower(s.name)")
    List<ProductionSchema> findActive(UUID storeId);

    Optional<ProductionSchema> findByIdAndStoreId(UUID id, UUID storeId);

    @Query("""
            select count(s) > 0 from ProductionSchema s
            where s.storeId = :storeId and s.deletedAt is null and upper(s.name) = upper(:name)
              and (:exceptId is null or s.id <> :exceptId)""")
    boolean nameTaken(UUID storeId, String name, UUID exceptId);
}
