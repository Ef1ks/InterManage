package com.intermanage.backend.meat.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.intermanage.backend.product.entity.Product;

/** Mięso z kartoteki sklepu: rejony 24 (mięso, wędzarnia) i 46 (drób), bez przypraw i dodatków z 2401. */
public interface MeatCatalogRepository extends Repository<Product, UUID> {

    @Query("""
            select p from Product p where p.store.id = :storeId and p.regionCode in ('24', '46')
            and p.familyCode <> '2401' order by p.brand, p.name""")
    List<Product> findMeat(UUID storeId);

    @Query("select p from Product p where p.store.id = :storeId and p.id in :ids")
    List<Product> findInStore(UUID storeId, List<UUID> ids);
}
