package com.intermanage.backend.sales.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.sales.entity.LossControlGroupArticle;

public interface LossControlGroupArticleRepository extends JpaRepository<LossControlGroupArticle, UUID> {

    /** Podpięte artykuły wszystkich aktywnych grup sklepu. */
    @Query("""
            select article from LossControlGroupArticle article
            join fetch article.group lossGroup
            where lossGroup.store.id = :storeId and lossGroup.deletedAt is null and article.removedAt is null
            order by article.addedAt""")
    List<LossControlGroupArticle> findActiveInStore(UUID storeId);

    @Query("""
            select article from LossControlGroupArticle article
            where article.group.id = :groupId and article.removedAt is null and article.itm8 in :itm8s""")
    List<LossControlGroupArticle> findActive(UUID groupId, Collection<String> itm8s);
}
