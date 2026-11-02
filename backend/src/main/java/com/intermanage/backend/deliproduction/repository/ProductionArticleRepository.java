package com.intermanage.backend.deliproduction.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliproduction.entity.ProductionArticle;

public interface ProductionArticleRepository extends JpaRepository<ProductionArticle, UUID> {

    @Query("""
            select a from ProductionArticle a join fetch a.schema
            where a.storeId = :storeId and a.deletedAt is null order by a.name""")
    List<ProductionArticle> findActive(UUID storeId);

    @Query("select a from ProductionArticle a join fetch a.schema where a.id = :id and a.storeId = :storeId")
    Optional<ProductionArticle> findInStore(UUID storeId, UUID id);

    @Query("""
            select a from ProductionArticle a join fetch a.schema
            where a.storeId = :storeId and a.id in :ids and a.deletedAt is null""")
    List<ProductionArticle> findActive(UUID storeId, Collection<UUID> ids);

    @Query("""
            select count(a) > 0 from ProductionArticle a
            where a.storeId = :storeId and a.deletedAt is null and a.code = :code
              and (:exceptId is null or a.id <> :exceptId)""")
    boolean codeTaken(UUID storeId, String code, UUID exceptId);

    @Query("select count(a) from ProductionArticle a where a.schema.id = :schemaId and a.deletedAt is null")
    long countBySchema(UUID schemaId);
}
