package com.intermanage.backend.collector.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.CollectorListLine;

public interface CollectorListLineRepository extends JpaRepository<CollectorListLine, UUID> {

    @Query("select l from CollectorListLine l where l.listId in :listIds order by l.listId, l.position")
    List<CollectorListLine> findByLists(Collection<UUID> listIds);
}
