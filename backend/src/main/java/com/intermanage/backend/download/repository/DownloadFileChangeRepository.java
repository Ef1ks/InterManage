package com.intermanage.backend.download.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.download.entity.DownloadFileChange;

public interface DownloadFileChangeRepository extends JpaRepository<DownloadFileChange, UUID> {

    /** Obowiązujące wpisy list zmian wersji, w kolejności wpisania. */
    @Query("""
            select c from DownloadFileChange c
            where c.fileId in :fileIds and c.removedAt is null order by c.position""")
    List<DownloadFileChange> findActive(Collection<UUID> fileIds);
}
