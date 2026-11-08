package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffShift;

public interface StaffShiftRepository extends JpaRepository<StaffShift, UUID> {

    @Query("""
            select s from StaffShift s
            where s.position.id in :positionIds and s.store.id = :storeId and s.deletedAt is null
            order by s.start, s.end""")
    List<StaffShift> findActiveOfPositions(Collection<UUID> positionIds, UUID storeId);

    @Query("""
            select s from StaffShift s
            where s.employee.id in :employeeIds and s.deletedAt is null order by s.start, s.end""")
    List<StaffShift> findActiveOfEmployees(Collection<UUID> employeeIds);
}
