package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleDayOff;

public interface StaffScheduleDayOffRepository extends JpaRepository<StaffScheduleDayOff, UUID> {

    @EntityGraph(attributePaths = {"employee", "schedule", "schedule.store", "schedule.department"})
    @Query("""
            select d from StaffScheduleDayOff d
            where d.schedule.id = :scheduleId and d.deletedAt is null order by d.day""")
    List<StaffScheduleDayOff> findActive(UUID scheduleId);

    @EntityGraph(attributePaths = {"employee", "schedule", "schedule.store", "schedule.department"})
    @Query("""
            select d from StaffScheduleDayOff d
            where d.employee.id in :employeeIds and d.day between :from and :to and d.deletedAt is null
            order by d.day""")
    List<StaffScheduleDayOff> findOfEmployees(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    @Query("""
            select count(d) > 0 from StaffScheduleDayOff d
            where d.employee.id = :employeeId and d.forDate = :forDate and d.deletedAt is null""")
    boolean existsFor(UUID employeeId, LocalDate forDate);
}
