package com.intermanage.backend.staff.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimePunch;

public interface StaffTimePunchRepository extends JpaRepository<StaffTimePunch, UUID> {

    @EntityGraph(attributePaths = "employee")
    @Query("""
            select p from StaffTimePunch p
            where p.employee.id in :employeeIds and p.punchedAt >= :from and p.punchedAt < :to and p.deletedAt is null
            order by p.punchedAt""")
    List<StaffTimePunch> findOfEmployees(Collection<UUID> employeeIds, Instant from, Instant to);

    /** Pierwsze odbicie pracownika: od tego dnia prowadzi się dla niego kartę czasu pracy. */
    interface FirstPunch {

        UUID getEmployeeId();

        Instant getPunchedAt();
    }

    @Query("""
            select p.employee.id as employeeId, min(p.punchedAt) as punchedAt from StaffTimePunch p
            where p.employee.id in :employeeIds and p.deletedAt is null group by p.employee.id""")
    List<FirstPunch> findFirst(Collection<UUID> employeeIds);

    /** Pracownicy, którzy odbili się w tym sklepie od podanej chwili (obecni mogą być spoza grafiku). */
    @Query("""
            select distinct p.employee.id from StaffTimePunch p
            where p.store.id = :storeId and p.punchedAt >= :from and p.deletedAt is null""")
    List<UUID> findEmployeeIdsInStore(UUID storeId, Instant from);
}
