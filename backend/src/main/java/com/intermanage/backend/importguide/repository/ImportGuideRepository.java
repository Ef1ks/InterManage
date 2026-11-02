package com.intermanage.backend.importguide.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.importguide.entity.ImportGuide;
import com.intermanage.backend.importguide.entity.ImportReportKind;

public interface ImportGuideRepository extends JpaRepository<ImportGuide, UUID> {

    @EntityGraph(attributePaths = "createdBy")
    List<ImportGuide> findByDeletedAtIsNull();

    Optional<ImportGuide> findByKindAndDeletedAtIsNull(ImportReportKind kind);
}
