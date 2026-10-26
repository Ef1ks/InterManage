package com.intermanage.backend.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.ProductEan;
import com.intermanage.backend.entity.Store;

public interface ProductEanRepository extends JpaRepository<ProductEan, UUID> {

    @Query("select pe from ProductEan pe where pe.product.store = :store")
    List<ProductEan> findByStore(Store store);
}
