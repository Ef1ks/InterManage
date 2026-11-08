package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffLeave;

public interface StaffLeaveRepository extends JpaRepository<StaffLeave, UUID> {

    @Query("select x from StaffLeave x where x.employee.id in :employeeIds and x.deletedAt is null")
    List<StaffLeave> findActive(Collection<UUID> employeeIds);
}
