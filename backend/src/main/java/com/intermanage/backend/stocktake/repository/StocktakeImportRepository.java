package com.intermanage.backend.stocktake.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.stocktake.entity.StocktakeImport;

public interface StocktakeImportRepository extends JpaRepository<StocktakeImport, UUID> {

    @EntityGraph(attributePaths = {"importedBy", "stocktake"})
    List<StocktakeImport> findByStocktake_IdOrderByImportedAtDesc(UUID stocktakeId);
}
