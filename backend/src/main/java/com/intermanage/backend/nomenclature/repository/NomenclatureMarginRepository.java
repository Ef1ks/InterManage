package com.intermanage.backend.nomenclature.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.nomenclature.entity.NomenclatureMargin;

public interface NomenclatureMarginRepository extends JpaRepository<NomenclatureMargin, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    List<NomenclatureMargin> findByStoreId(UUID storeId);
}
