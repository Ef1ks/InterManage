package com.intermanage.backend.staff.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffEmployerDetails;

public interface StaffEmployerDetailsRepository extends JpaRepository<StaffEmployerDetails, UUID> {

    @Query("select e from StaffEmployerDetails e where e.companyKey = :companyKey and e.deletedAt is null")
    Optional<StaffEmployerDetails> findActive(String companyKey);
}
