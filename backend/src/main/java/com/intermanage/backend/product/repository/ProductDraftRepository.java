package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductDraft;

public interface ProductDraftRepository extends JpaRepository<ProductDraft, UUID> {

    @Query("select d from ProductDraft d where d.store.id = :storeId and d.matchedAt is null and d.removedAt is null")
    List<ProductDraft> findPending(UUID storeId);

    @Query("""
            select d from ProductDraft d
            where d.store.id = :storeId and d.ean in :eans and d.matchedAt is null and d.removedAt is null
            """)
    List<ProductDraft> findPendingByEans(UUID storeId, Collection<String> eans);

    List<ProductDraft> findByIdInAndStoreId(Collection<UUID> ids, UUID storeId);

    /**
     * Które z EAN-ów (bez zer wiodących) są już w kartotece sklepu: bieżący EAN artykułu, dawne pary ITM8 + EAN
     * albo obowiązujące multikody. Takie artykuły są w pliku CADBEZP jako zmienione, nie nowe.
     */
    @Query(value = """
            select k.ean from (
                select ltrim(p.ean, '0') as ean from products p where p.store_id = :storeId
                union select ltrim(e.ean, '0') from product_eans e join products p on p.id = e.product_id
                      where p.store_id = :storeId
                union select ltrim(c.ean, '0') from product_codes c where c.store_id = :storeId and c.current
            ) k where k.ean = any(cast(:eans as varchar[]))
            """, nativeQuery = true)
    List<String> findKnownEans(UUID storeId, String[] eans);
}
