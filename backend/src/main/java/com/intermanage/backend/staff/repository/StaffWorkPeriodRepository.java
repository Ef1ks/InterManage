package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffWorkPeriod;

public interface StaffWorkPeriodRepository extends JpaRepository<StaffWorkPeriod, UUID> {

    @Query("select x from StaffWorkPeriod x where x.employee.id in :employeeIds and x.deletedAt is null")
    List<StaffWorkPeriod> findActive(Collection<UUID> employeeIds);
}
