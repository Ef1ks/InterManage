package com.intermanage.backend.deliorder.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.deliorder.entity.OrderCategoryProduct;

public interface OrderCategoryProductRepository extends JpaRepository<OrderCategoryProduct, UUID> {

    /** Pozycje formularza sklepu-dostawcy razem z kategorią, działem i produktem (do złożenia zamówienia). */
    @Query("""
            select item from OrderCategoryProduct item
            join fetch item.category c join fetch c.section s join fetch item.product
            where item.id in :ids and s.store.id = :formStoreId""")
    List<OrderCategoryProduct> findInForm(Collection<UUID> ids, UUID formStoreId);
}
