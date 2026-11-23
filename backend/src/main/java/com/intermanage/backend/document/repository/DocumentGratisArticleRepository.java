package com.intermanage.backend.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.document.entity.DocumentGratisArticle;

public interface DocumentGratisArticleRepository extends JpaRepository<DocumentGratisArticle, UUID> {

    @Query("""
            select article from DocumentGratisArticle article
            join fetch article.product product
            left join fetch article.addedBy
            where product.store.id = :storeId and article.removedAt is null
            order by product.name, product.itm8""")
    List<DocumentGratisArticle> findActive(UUID storeId);

    @Query("""
            select article.product.itm8 from DocumentGratisArticle article
            where article.product.store.id = :storeId and article.removedAt is null""")
    Set<String> findActiveItm8s(UUID storeId);

    @Query("""
            select article from DocumentGratisArticle article
            join fetch article.product product
            left join fetch article.addedBy
            where product.id = :productId and article.removedAt is null""")
    Optional<DocumentGratisArticle> findActiveByProduct(UUID productId);

    @Query("""
            select article from DocumentGratisArticle article
            where article.id = :id and article.product.store.id = :storeId and article.removedAt is null""")
    Optional<DocumentGratisArticle> findActiveInStore(UUID storeId, UUID id);

    /** Czy dokument ma artykuł z listy gratisów sklepu na minusie i na plusie. */
    interface Marks {
        UUID getDocumentId();

        boolean getOnMinus();

        boolean getOnPlus();
    }

    @Query(value = """
            select l.document_id as documentId,
                   bool_or(l.quantity < 0) as onMinus,
                   bool_or(l.quantity > 0) as onPlus
            from document_lines l
            join products p on p.store_id = :storeId and p.itm8 = l.itm8
            join document_gratis_articles g on g.product_id = p.id and g.removed_at is null
            where l.document_id in (:documentIds)
            group by l.document_id""", nativeQuery = true)
    List<Marks> findMarks(UUID storeId, Collection<UUID> documentIds);
}
