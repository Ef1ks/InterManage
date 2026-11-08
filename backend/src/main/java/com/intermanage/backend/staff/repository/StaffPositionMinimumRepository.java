package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffPositionMinimum;

public interface StaffPositionMinimumRepository extends JpaRepository<StaffPositionMinimum, UUID> {

    @EntityGraph(attributePaths = "position")
    @Query("""
            select m from StaffPositionMinimum m
            where m.store.id = :storeId and m.position.department.id = :departmentId and m.deletedAt is null""")
    List<StaffPositionMinimum> findActive(UUID storeId, UUID departmentId);
}
