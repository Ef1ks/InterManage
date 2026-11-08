package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDisability;

public interface StaffDisabilityRepository extends JpaRepository<StaffDisability, UUID> {

    @Query("select x from StaffDisability x where x.employee.id in :employeeIds and x.deletedAt is null")
    List<StaffDisability> findActive(Collection<UUID> employeeIds);
}
