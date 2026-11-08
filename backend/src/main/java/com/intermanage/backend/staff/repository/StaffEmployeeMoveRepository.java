package com.intermanage.backend.staff.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.staff.entity.StaffEmployeeMove;

public interface StaffEmployeeMoveRepository extends JpaRepository<StaffEmployeeMove, UUID> {
}
