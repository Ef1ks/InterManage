package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeRequestEvent;

public interface StaffTimeRequestEventRepository extends JpaRepository<StaffTimeRequestEvent, UUID> {

    @Query("""
            select x from StaffTimeRequestEvent x left join fetch x.createdBy
            where x.request.id in :requestIds order by x.createdAt, x.id""")
    List<StaffTimeRequestEvent> findOf(Collection<UUID> requestIds);
}
