package com.intermanage.backend.staff.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffFlexibleTimeSetting;

public interface StaffFlexibleTimeSettingRepository extends JpaRepository<StaffFlexibleTimeSetting, UUID> {

    @Query("select s from StaffFlexibleTimeSetting s where s.store.id = :storeId and s.deletedAt is null")
    Optional<StaffFlexibleTimeSetting> findActive(UUID storeId);
}
