package com.intermanage.backend.product.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.Product;
import com.intermanage.backend.product.entity.ProductImport;
import com.intermanage.backend.store.entity.Store;

/** Wyszukiwanie produktów po słowach: {@link ProductSearch}. */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    List<Product> findByStore(Store store);

    long countByStoreId(UUID storeId);

    Optional<Product> findByIdAndStoreId(UUID id, UUID storeId);

    Optional<Product> findByStoreIdAndItm8(UUID storeId, String itm8);

    List<Product> findByIdInAndStoreId(Collection<UUID> ids, UUID storeId);

    /** Produkty sklepu po bieżącym EAN-ie (np. lista wklejona z Excela). */
    List<Product> findByStoreIdAndEanIn(UUID storeId, Collection<String> eans);

    List<Product> findByStoreIdAndItm8In(UUID storeId, Collection<String> itm8s);

    /** Artykuł z ceną sprzedaży narzuconą przez centralę (CENA_CAD). */
    interface CadencierPrice {
        String getItm8();

        BigDecimal getCadencierPrice();
    }

    /** CENA_CAD podanych artykułów (tablica, bo ITM8 bywa kilka tysięcy). */
    @Query(value = """
            select p.itm8 as itm8, p.cadencier_price as cadencier_price from products p
            where p.store_id = :storeId and p.cadencier_price > 0 and p.itm8 = any(cast(:itm8s as varchar[]))
            """, nativeQuery = true)
    List<CadencierPrice> findCadencierPrices(UUID storeId, String[] itm8s);

    /** Produkty sklepu, których nie było w danym imporcie (zostają w bazie z danymi z wcześniejszego). */
    long countByStoreAndLastSeenImportNot(Store store, ProductImport productImport);

    /**
     * Blokada doradcza Postgresa na czas transakcji: dwa importy do tego samego sklepu wykonają się po kolei.
     * Funkcja zwraca {@code void}, więc owijamy ją w zapytanie zwracające liczbę.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);

    /** Wszystkie EAN-y artykułu: bieżący, dawne pary ITM8 + EAN i obowiązujące multikody. */
    @Query(value = """
            select p.ean from products p where p.id = :productId
            union select e.ean from product_eans e where e.product_id = :productId
            union select c.ean from product_codes c
                  where c.store_id = :storeId and c.itm8 = :itm8 and c.current
            """, nativeQuery = true)
    List<String> findAllEans(UUID storeId, UUID productId, String itm8);
}
