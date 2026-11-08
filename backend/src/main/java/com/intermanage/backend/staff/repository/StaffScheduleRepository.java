package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffSchedule;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, UUID> {

    @Query("""
            select s from StaffSchedule s
            where s.store.id = :storeId and s.department.id = :departmentId and s.month = :month""")
    Optional<StaffSchedule> find(UUID storeId, UUID departmentId, LocalDate month);
}
