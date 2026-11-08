package com.intermanage.backend.download.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.download.entity.DownloadFile;

/** Wersje plików; obowiązuje niezastąpiona ({@code replacedAt is null}), najwyżej jedna na pozycję. */
public interface DownloadFileRepository extends JpaRepository<DownloadFile, UUID> {

    /** Opis obowiązującej wersji bez treści. */
    interface Summary {
        UUID getDownloadId();

        UUID getId();

        String getFileName();

        String getVersion();

        int getSizeBytes();

        String getSha256();

        Instant getUploadedAt();

        String getUploadedBy();
    }

    @Query("""
            select f.download.id as downloadId, f.id as id, f.fileName as fileName, f.version as version,
                f.sizeBytes as sizeBytes, f.sha256 as sha256, f.createdAt as uploadedAt,
                u.displayName as uploadedBy
            from DownloadFile f left join f.createdBy u where f.replacedAt is null""")
    List<Summary> findCurrentSummaries();

    @Query("select f from DownloadFile f where f.download.id = :downloadId and f.replacedAt is null")
    Optional<DownloadFile> findCurrent(UUID downloadId);
}
