package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffContractChange;

public interface StaffContractChangeRepository extends JpaRepository<StaffContractChange, UUID> {

    @Query("""
            select x from StaffContractChange x join fetch x.contract
            where x.employee.id in :employeeIds and x.deletedAt is null order by x.validFrom""")
    List<StaffContractChange> findActive(Collection<UUID> employeeIds);
}
