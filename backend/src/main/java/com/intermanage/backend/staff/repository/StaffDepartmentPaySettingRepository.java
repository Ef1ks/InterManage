package com.intermanage.backend.staff.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDepartmentPaySetting;

public interface StaffDepartmentPaySettingRepository extends JpaRepository<StaffDepartmentPaySetting, UUID> {

    @Query("""
            select s from StaffDepartmentPaySetting s
            where s.store.id = :storeId and s.department.id = :departmentId and s.deletedAt is null""")
    Optional<StaffDepartmentPaySetting> findActive(UUID storeId, UUID departmentId);
}
