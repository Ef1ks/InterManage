package com.intermanage.backend.staff.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.staff.entity.StaffProfile;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {
}
