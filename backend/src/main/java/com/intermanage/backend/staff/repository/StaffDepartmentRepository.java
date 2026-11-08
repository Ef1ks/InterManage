package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDepartment;

public interface StaffDepartmentRepository extends JpaRepository<StaffDepartment, UUID> {

    @Query("""
            select d from StaffDepartment d
            where d.companyKey = :companyKey and d.deletedAt is null
            order by lower(d.name)""")
    List<StaffDepartment> findActive(String companyKey);

    @Query("select d from StaffDepartment d where d.companyKey = :companyKey")
    List<StaffDepartment> findAllInCompany(String companyKey);

    @Query("select d from StaffDepartment d where d.companyKey = :companyKey and d.id = :id")
    Optional<StaffDepartment> findInCompany(String companyKey, UUID id);

    @Query("""
            select count(d) > 0 from StaffDepartment d
            where d.companyKey = :companyKey and d.deletedAt is null and lower(d.name) = lower(:name)
                and (:exceptId is null or d.id <> :exceptId)""")
    boolean existsActiveName(String companyKey, String name, UUID exceptId);
}
