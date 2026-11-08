package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleMember;

public interface StaffScheduleMemberRepository extends JpaRepository<StaffScheduleMember, UUID> {

    @EntityGraph(attributePaths = {"employee", "group"})
    @Query("select m from StaffScheduleMember m where m.store.id = :storeId and m.department.id = :departmentId")
    List<StaffScheduleMember> find(UUID storeId, UUID departmentId);
}
