package com.intermanage.backend.mail.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.mail.entity.OutgoingMail;

public interface OutgoingMailRepository extends JpaRepository<OutgoingMail, UUID> {

    @Query("""
            select m from OutgoingMail m
            where m.sentAt is null and m.failedAt is null and m.nextAttemptAt <= :now
            order by m.nextAttemptAt, m.createdAt""")
    List<OutgoingMail> findDue(Instant now, Pageable page);
}
