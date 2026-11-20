package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductEan;
import com.intermanage.backend.store.entity.Store;

public interface ProductEanRepository extends JpaRepository<ProductEan, UUID> {

    @Query("select pe from ProductEan pe where pe.product.store = :store")
    List<ProductEan> findByStore(Store store);

    /** Pary podanych produktów, z importami (import kartoteki porównuje je po datach importów). */
    @Query("""
            select pe from ProductEan pe join fetch pe.firstSeenImport join fetch pe.lastSeenImport
            where pe.product in :products""")
    List<ProductEan> findByProductIn(Collection<Product> products);

    /** Pary podanych EAN-ów w sklepie sprzed importu {@code importId} (bez par dopisanych w nim samym). */
    @Query("""
            select pe from ProductEan pe join fetch pe.product join fetch pe.firstSeenImport
            join fetch pe.lastSeenImport
            where pe.product.store.id = :storeId and pe.ean in :eans and pe.firstSeenImport.id <> :importId""")
    List<ProductEan> findOfEansBefore(UUID storeId, Collection<String> eans, UUID importId);

    /**
     * Pary ITM8 + EAN z wierszy paczki były w imporcie (tablice tej samej długości, wiersz = pozycja). Jedno zapytanie
     * zamiast UPDATE-u encji na każdą parę; pomija pary, które już mają ten import (dopisane w nim samym).
     */
    @Modifying
    @Query(value = """
            update product_eans e set last_seen_import_id = :importId
            from products p, unnest(cast(:itm8s as varchar[]), cast(:eans as varchar[])) as r(itm8, ean)
            where p.store_id = :storeId and p.itm8 = r.itm8 and e.product_id = p.id and e.ean = r.ean
              and e.last_seen_import_id <> :importId
            """, nativeQuery = true)
    int markSeenInImport(UUID storeId, UUID importId, String[] itm8s, String[] eans);

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
