package com.intermanage.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.entity.NomenclatureImport;

public interface NomenclatureImportRepository extends JpaRepository<NomenclatureImport, UUID> {

    @Query("select i from NomenclatureImport i left join fetch i.importedBy where i.store.id = :storeId order by i.importedAt desc, i.id desc")
    List<NomenclatureImport> findLatest(UUID storeId, Limit limit);

    @Query("select max(i.reportGeneratedAt) from NomenclatureImport i where i.store.id = :storeId")
    Optional<LocalDateTime> findNewestReportGeneratedAt(UUID storeId);
}
