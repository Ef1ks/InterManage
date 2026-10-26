package com.intermanage.backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.intermanage.backend.entity.KsefApiCall;

public interface KsefApiCallRepository extends JpaRepository<KsefApiCall, UUID> {

    @Query("""
            select c.calledAt from KsefApiCall c
            where c.nip = :nip and c.operation = :operation and c.calledAt > :since order by c.calledAt""")
    List<Instant> findCallTimes(String nip, String operation, Instant since);

    /** Wpisy starsze niż najdłuższe okno limitu nie są już potrzebne. */
    @Modifying
    @Transactional
    @Query("delete from KsefApiCall c where c.calledAt < :before")
    int deleteOlderThan(Instant before);
}
