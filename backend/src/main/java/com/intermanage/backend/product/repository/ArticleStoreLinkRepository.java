package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ArticleStoreLink;

public interface ArticleStoreLinkRepository extends JpaRepository<ArticleStoreLink, UUID> {

    /** Aktywne połączenia, w których oba sklepy są wśród podanych. */
    @Query("""
            select l from ArticleStoreLink l
            where l.deletedAt is null and l.storeId in :storeIds and l.linkedStoreId in :storeIds
            """)
    List<ArticleStoreLink> findActiveBetween(Collection<UUID> storeIds);

    /** Aktywne połączenia artykułu sklepu z artykułem w drugim sklepie (z dowolnej strony). */
    @Query("""
            select l from ArticleStoreLink l
            where l.deletedAt is null
              and ((l.storeId = :storeId and l.itm8 = :itm8 and l.linkedStoreId = :otherStoreId)
                or (l.linkedStoreId = :storeId and l.linkedItm8 = :itm8 and l.storeId = :otherStoreId))
            """)
    List<ArticleStoreLink> findActiveOf(UUID storeId, String itm8, UUID otherStoreId);
}
