package com.intermanage.backend.collector.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.CollectorFile;
import com.intermanage.backend.collector.entity.CollectorFileLine;

public interface CollectorFileRepository extends JpaRepository<CollectorFile, UUID> {

    @Query("""
            select f from CollectorFile f left join fetch f.createdBy left join fetch f.loadedBy
            where f.storeId = :storeId and f.deletedAt is null order by f.createdAt desc""")
    List<CollectorFile> findLatest(UUID storeId, Limit limit);

    @Query("""
            select count(f) from CollectorFile f
            where f.storeId = :storeId and f.deletedAt is null and f.status = 'WAITING' and f.kind = :kind""")
    long countWaiting(UUID storeId, CollectorFile.Kind kind);

    @Query("select l from CollectorFileLine l where l.fileId = :fileId order by l.position")
    List<CollectorFileLine> findLines(UUID fileId);

    @Query("select l.fileId as fileId, count(l) as lines from CollectorFileLine l where l.fileId in :ids group by l.fileId")
    List<LineCount> countLines(Collection<UUID> ids);

    interface LineCount {
        UUID getFileId();

        long getLines();
    }

    /** Ile list kolektora złożyło się na pozycję (0 = z tabeli wprowadzania danych albo Protokołów). */
    @Query(value = "select count(*) from collector_file_sources where file_id = :fileId", nativeQuery = true)
    long countSources(UUID fileId);

    @Modifying
    @Query(value = "insert into collector_file_sources (file_id, list_id) values (:fileId, :listId) on conflict do nothing",
            nativeQuery = true)
    void addSource(UUID fileId, UUID listId);
}
