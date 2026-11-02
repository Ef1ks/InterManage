package com.intermanage.backend.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductCode;

/** Obowiązujące kody z multikodów: wszystkie EAN-y artykułów sklepu, nie tylko główny z kartoteki. */
public interface ProductCodeRepository extends JpaRepository<ProductCode, UUID> {

    @Query("select c from ProductCode c where c.storeId = :storeId and c.current and c.ean in :eans")
    List<ProductCode> findCurrentByEans(UUID storeId, Collection<String> eans);

    @Query("select c from ProductCode c where c.storeId = :storeId and c.current and c.itm8 in :itm8s")
    List<ProductCode> findCurrentByItm8s(UUID storeId, Collection<String> itm8s);
}
