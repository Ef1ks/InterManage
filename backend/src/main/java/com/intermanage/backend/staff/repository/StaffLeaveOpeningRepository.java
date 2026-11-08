package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffLeaveOpening;

public interface StaffLeaveOpeningRepository extends JpaRepository<StaffLeaveOpening, UUID> {

    @Query("select x from StaffLeaveOpening x where x.employee.id in :employeeIds and x.deletedAt is null")
    List<StaffLeaveOpening> findActive(Collection<UUID> employeeIds);
}
