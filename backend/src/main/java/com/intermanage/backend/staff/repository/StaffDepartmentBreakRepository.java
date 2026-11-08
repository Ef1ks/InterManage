package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDepartmentBreak;

public interface StaffDepartmentBreakRepository extends JpaRepository<StaffDepartmentBreak, UUID> {

    @Query("""
            select b from StaffDepartmentBreak b
            where b.store.id = :storeId and b.department.id = :departmentId and b.deletedAt is null
            order by b.start, b.end""")
    List<StaffDepartmentBreak> findActive(UUID storeId, UUID departmentId);
}
