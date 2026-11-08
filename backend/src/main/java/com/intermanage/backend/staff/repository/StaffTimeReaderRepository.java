package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeReader;

public interface StaffTimeReaderRepository extends JpaRepository<StaffTimeReader, UUID> {

    @Query("select r from StaffTimeReader r where r.store.id = :storeId and r.deletedAt is null order by r.createdAt")
    List<StaffTimeReader> findActive(UUID storeId);

    @EntityGraph(attributePaths = "store")
    @Query("select r from StaffTimeReader r where r.tokenHash = :tokenHash and r.deletedAt is null")
    Optional<StaffTimeReader> findByToken(String tokenHash);
}
