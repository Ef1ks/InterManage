package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffOvertimeTimeOff;

public interface StaffOvertimeTimeOffRepository extends JpaRepository<StaffOvertimeTimeOff, UUID> {

    /** Wpisy pracowników ze wszystkich sklepów: saldo okresu liczy się razem, gdzie by nie pracowali. */
    @EntityGraph(attributePaths = {"employee", "store"})
    @Query("""
            select t from StaffOvertimeTimeOff t
            where t.employee.id in :employeeIds and t.workDate between :from and :to and t.deletedAt is null
            order by t.workDate""")
    List<StaffOvertimeTimeOff> findOfEmployees(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = {"employee", "store"})
    @Query("""
            select t from StaffOvertimeTimeOff t
            where t.employee.id = :employeeId and t.workDate = :day and t.deletedAt is null""")
    Optional<StaffOvertimeTimeOff> findActive(UUID employeeId, LocalDate day);
}
