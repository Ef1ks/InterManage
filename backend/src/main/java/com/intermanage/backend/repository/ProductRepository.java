package com.intermanage.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.Product;
import com.intermanage.backend.entity.ProductImport;
import com.intermanage.backend.entity.Store;

/** Wyszukiwanie produktów po słowach: {@link ProductSearch}. */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    List<Product> findByStore(Store store);

    long countByStoreId(UUID storeId);

    Optional<Product> findByIdAndStoreId(UUID id, UUID storeId);

    List<Product> findByIdInAndStoreId(Collection<UUID> ids, UUID storeId);

    /** Produkty sklepu po bieżącym EAN-ie (np. lista wklejona z Excela). */
    List<Product> findByStoreIdAndEanIn(UUID storeId, Collection<String> eans);

    /** Produkty sklepu, których nie było w danym imporcie (zostają w bazie z danymi z wcześniejszego). */
    long countByStoreAndLastSeenImportNot(Store store, ProductImport productImport);

    /**
     * Blokada doradcza Postgresa na czas transakcji: dwa importy do tego samego sklepu wykonają się po kolei.
     * Funkcja zwraca {@code void}, więc owijamy ją w zapytanie zwracające liczbę.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockForImport(String key);
}
