package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleGroup;

public interface StaffScheduleGroupRepository extends JpaRepository<StaffScheduleGroup, UUID> {

    @Query("""
            select g from StaffScheduleGroup g
            where g.store.id = :storeId and g.department.id = :departmentId and g.deletedAt is null
            order by g.position""")
    List<StaffScheduleGroup> findActive(UUID storeId, UUID departmentId);
}
