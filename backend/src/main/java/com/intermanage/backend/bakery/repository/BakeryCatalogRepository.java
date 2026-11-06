package com.intermanage.backend.bakery.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.Product;

/** Artykuły kartoteki do tabel WZ. {@code products.supplier} to nazwa z raportu, ucięta do 30 znaków. */
public interface BakeryCatalogRepository extends Repository<Product, UUID> {

    @Query("select distinct p.supplier from Product p where p.store.id = :storeId and p.supplier is not null")
    List<String> findSupplierNames(UUID storeId);

    @Query("""
            select p from Product p where p.store.id = :storeId and p.supplier in :names
            and (p.active is null or p.active = true)""")
    List<Product> findActiveOfSuppliers(UUID storeId, Collection<String> names);

    @Query("select p from Product p where p.store.id = :storeId and p.id in :ids")
    List<Product> findInStore(UUID storeId, Collection<UUID> ids);
}
