package com.intermanage.backend.collector.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.collector.entity.CollectorListProtocol;

public interface CollectorListProtocolRepository extends JpaRepository<CollectorListProtocol, UUID> {

    @Query("""
            select p from CollectorListProtocol p left join fetch p.createdBy
            where p.listId in :listIds and p.undoneAt is null order by p.createdAt""")
    List<CollectorListProtocol> findByLists(Collection<UUID> listIds);
}
