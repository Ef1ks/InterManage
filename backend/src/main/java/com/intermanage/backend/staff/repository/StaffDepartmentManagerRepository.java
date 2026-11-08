package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffDepartmentManager;

public interface StaffDepartmentManagerRepository extends JpaRepository<StaffDepartmentManager, UUID> {

    @EntityGraph(attributePaths = "user")
    @Query("select m from StaffDepartmentManager m where m.department.id = :departmentId and m.deletedAt is null")
    List<StaffDepartmentManager> findActive(UUID departmentId);

    @Query("select m.department.id from StaffDepartmentManager m where m.user.id = :userId and m.deletedAt is null")
    List<UUID> findDepartmentIds(UUID userId);
}
