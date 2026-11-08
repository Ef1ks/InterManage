package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffContract;

public interface StaffContractRepository extends JpaRepository<StaffContract, UUID> {

    @Query("select x from StaffContract x where x.employee.id in :employeeIds and x.deletedAt is null")
    List<StaffContract> findActive(Collection<UUID> employeeIds);
}
