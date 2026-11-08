package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeDecision;

public interface StaffTimeDecisionRepository extends JpaRepository<StaffTimeDecision, UUID> {

    @EntityGraph(attributePaths = {"employee", "createdBy"})
    @Query("""
            select d from StaffTimeDecision d
            where d.employee.id in :employeeIds and d.workDate between :from and :to and d.deletedAt is null""")
    List<StaffTimeDecision> findActive(Collection<UUID> employeeIds, LocalDate from, LocalDate to);
}
