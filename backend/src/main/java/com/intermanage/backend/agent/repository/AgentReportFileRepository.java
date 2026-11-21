package com.intermanage.backend.agent.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.agent.entity.AgentReportFile;

public interface AgentReportFileRepository extends JpaRepository<AgentReportFile, UUID> {

    /** Próby wgrania plików raportu o tych nazwach (małymi literami), od najnowszej. */
    @Query("""
            select f from AgentReportFile f
            where f.storeId = :storeId and f.kind = :kind and lower(f.fileName) in :names
            order by f.startedAt desc""")
    List<AgentReportFile> findByNames(UUID storeId, String kind, Collection<String> names);

    @Query("select f from AgentReportFile f where f.storeId = :storeId order by f.startedAt desc")
    List<AgentReportFile> findLatest(UUID storeId, Limit limit);
}
