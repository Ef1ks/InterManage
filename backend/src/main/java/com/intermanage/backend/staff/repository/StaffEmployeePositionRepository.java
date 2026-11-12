package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffEmployeePosition;

public interface StaffEmployeePositionRepository extends JpaRepository<StaffEmployeePosition, UUID> {

    /** Dodatkowe stanowiska pracowników, tylko te, które nie są usunięte w strukturze. */
    @EntityGraph(attributePaths = {"employee", "position"})
    @Query("""
            select p from StaffEmployeePosition p
            where p.employee.id in :employeeIds and p.deletedAt is null and p.position.deletedAt is null""")
    List<StaffEmployeePosition> findActive(Collection<UUID> employeeIds);

    /** Wszystkie aktywne wpisy pracownika, także na usuniętych stanowiskach (zapis listy je zastępuje). */
    @Query("select p from StaffEmployeePosition p where p.employee.id = :employeeId and p.deletedAt is null")
    List<StaffEmployeePosition> findAllActive(UUID employeeId);
}
