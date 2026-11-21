package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffPosition;

public interface StaffPositionRepository extends JpaRepository<StaffPosition, UUID> {

    @Query("""
            select p from StaffPosition p join fetch p.department d
            where d.companyKey = :companyKey and p.deletedAt is null and d.deletedAt is null
            order by lower(p.name)""")
    List<StaffPosition> findActive(String companyKey);

    @Query("select p from StaffPosition p where p.department.companyKey = :companyKey and p.id = :id")
    Optional<StaffPosition> findInCompany(String companyKey, UUID id);

    @Query("""
            select count(p) > 0 from StaffPosition p
            where p.department.id = :departmentId and p.deletedAt is null and lower(p.name) = lower(:name)
                and (:exceptId is null or p.id <> :exceptId)""")
    boolean existsActiveName(UUID departmentId, String name, UUID exceptId);

    @Query("select count(p) from StaffPosition p where p.department.id = :departmentId and p.deletedAt is null")
    long countActiveInDepartment(UUID departmentId);

    @Query("select count(p) from StaffPosition p where p.department.companyKey = :companyKey and p.roleKey = :roleKey"
            + " and p.deletedAt is null and p.department.deletedAt is null")
    long countActiveWithRoleKey(String companyKey, String roleKey);

    /** Przeniesienie dawnej roli stanowiska (role_id) na klucz rangi sklepu. */
    @Modifying
    @Query(value = "update staff_positions set role_key = :roleKey where role_id = :roleId and role_key is null",
            nativeQuery = true)
    int adoptRoleKey(UUID roleId, String roleKey);
}
