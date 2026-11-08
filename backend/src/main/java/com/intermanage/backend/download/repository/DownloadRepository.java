package com.intermanage.backend.download.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.download.entity.Download;

public interface DownloadRepository extends JpaRepository<Download, UUID> {

    /** Pozycje na stronie, w kolejności dodania. */
    List<Download> findByRemovedAtIsNullOrderByCreatedAt();
}
