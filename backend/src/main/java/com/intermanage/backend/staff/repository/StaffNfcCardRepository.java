package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffNfcCard;

public interface StaffNfcCardRepository extends JpaRepository<StaffNfcCard, UUID> {

    @Query("select c from StaffNfcCard c where c.employee.id in :employeeIds and c.deletedAt is null")
    List<StaffNfcCard> findActive(Collection<UUID> employeeIds);

    @EntityGraph(attributePaths = {"employee", "employee.user", "employee.store"})
    @Query("select c from StaffNfcCard c where c.cardUid = :cardUid and c.deletedAt is null")
    Optional<StaffNfcCard> findByUid(String cardUid);
}
