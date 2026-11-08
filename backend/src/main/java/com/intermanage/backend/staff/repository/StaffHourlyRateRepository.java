package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffHourlyRate;

public interface StaffHourlyRateRepository extends JpaRepository<StaffHourlyRate, UUID> {

    /** Stawki obowiązujące najpóźniej od {@code month}: obowiązuje najnowsza z każdej pary pracownik + umowa. */
    @Query("""
            select r from StaffHourlyRate r join fetch r.employee
            where r.employee.id in :employeeIds and r.validFrom <= :month and r.deletedAt is null
            order by r.validFrom""")
    List<StaffHourlyRate> findUpTo(Collection<UUID> employeeIds, LocalDate month);
}
