package com.intermanage.backend.staff.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDepartmentTimeSetting;

public interface StaffDepartmentTimeSettingRepository extends JpaRepository<StaffDepartmentTimeSetting, UUID> {

    @Query("""
            select s from StaffDepartmentTimeSetting s
            where s.store.id = :storeId and s.department.id = :departmentId and s.deletedAt is null""")
    Optional<StaffDepartmentTimeSetting> findActive(UUID storeId, UUID departmentId);
}
