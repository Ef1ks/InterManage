package com.intermanage.backend.staff.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.staff.entity.StaffContractRevision;

public interface StaffContractRevisionRepository extends JpaRepository<StaffContractRevision, UUID> {
}
