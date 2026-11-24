package com.intermanage.backend.dataentry.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.dataentry.entity.ProductFileExport;

public interface ProductFileExportRepository extends JpaRepository<ProductFileExport, UUID> {

    Optional<ProductFileExport> findFirstByStoreIdAndContentSha256AndFileNameOrderByCreatedAtDesc(UUID storeId,
            String contentSha256, String fileName);
}
