package com.intermanage.backend.collector.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.CollectorList;

public interface CollectorListRepository extends JpaRepository<CollectorList, UUID> {

    @Query("""
            select l from CollectorList l join fetch l.collector
            where l.storeId = :storeId and l.deletedAt is null
            order by l.receivedAt desc, l.id desc""")
    List<CollectorList> findLatest(UUID storeId, Limit limit);

    @Query("""
            select l from CollectorList l join fetch l.collector
            where l.storeId = :storeId and l.id in :ids and l.deletedAt is null
            order by l.receivedAt, l.id""")
    List<CollectorList> findActive(UUID storeId, Collection<UUID> ids);

    /** Listy bez protokołu (licznik w menu). */
    @Query("""
            select count(l) from CollectorList l
            where l.storeId = :storeId and l.deletedAt is null and not exists (
                select 1 from CollectorListProtocol p where p.listId = l.id and p.undoneAt is null)""")
    long countWaiting(UUID storeId);
}
