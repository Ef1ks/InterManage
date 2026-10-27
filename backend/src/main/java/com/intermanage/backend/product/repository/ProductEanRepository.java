package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductEan;
import com.intermanage.backend.store.entity.Store;

public interface ProductEanRepository extends JpaRepository<ProductEan, UUID> {

    @Query("select pe from ProductEan pe where pe.product.store = :store")
    List<ProductEan> findByStore(Store store);

    /** Para EAN + ITM8 z kartoteki sklepu. */
    interface EanItm8 {
        String getEan();

        String getItm8();
    }

    /** Wszystkie EAN-y (także dawne), jakie miały podane artykuły w sklepie. */
    @Query("""
            select pe.ean as ean, p.itm8 as itm8 from ProductEan pe join pe.product p
            where p.store.id = :storeId and p.itm8 in :itm8s""")
    List<EanItm8> findEans(UUID storeId, Collection<String> itm8s);
}
