package com.intermanage.backend.product.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.product.entity.ProductEanChange;

public interface ProductEanChangeRepository extends JpaRepository<ProductEanChange, UUID> {

    @EntityGraph(attributePaths = {"product", "previousProduct"})
    @Query("select c from ProductEanChange c where c.productImport.id = :importId")
    List<ProductEanChange> findByImportId(UUID importId);
}
