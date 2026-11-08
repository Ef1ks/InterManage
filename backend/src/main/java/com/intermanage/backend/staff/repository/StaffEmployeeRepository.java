package com.intermanage.backend.staff.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffEmployee;

public interface StaffEmployeeRepository extends JpaRepository<StaffEmployee, UUID> {

    /** Także po zakończeniu pracy: panel pokazuje ich osobno, żeby dało się przywrócić osobę, która wraca. */
    @EntityGraph(attributePaths = {"store", "user", "user.role", "position", "position.department"})
    @Query("select e from StaffEmployee e where e.store.id in :storeIds order by lower(e.user.displayName)")
    List<StaffEmployee> findInStores(Collection<UUID> storeIds);

    @EntityGraph(attributePaths = {"store", "user", "user.role", "position", "position.department"})
    @Query("select e from StaffEmployee e where e.id = :id")
    Optional<StaffEmployee> findWithDetails(UUID id);

    @EntityGraph(attributePaths = {"user", "user.role"})
    @Query("select e from StaffEmployee e where e.position.id = :positionId and e.endedAt is null")
    List<StaffEmployee> findActiveOnPosition(UUID positionId);

    @Query("select count(e) from StaffEmployee e where e.position.id = :positionId and e.endedAt is null")
    long countActiveOnPosition(UUID positionId);

    @Query("""
            select e.position.id, count(e) from StaffEmployee e
            where e.position.department.companyKey = :companyKey and e.endedAt is null
            group by e.position.id""")
    List<Object[]> countActivePerPosition(String companyKey);

    @Query("select count(e) from StaffEmployee e where e.store.id = :storeId and e.endedAt is null")
    long countActiveInStore(UUID storeId);

    @Query("select count(e) > 0 from StaffEmployee e where e.user.id = :userId and e.endedAt is null")
    boolean existsActiveForUser(UUID userId);

    /** Id konta i nazwa stanowiska pracowników w trakcie pracy, do listy użytkowników. */
    @Query("select e.user.id, e.position.name from StaffEmployee e where e.endedAt is null")
    List<Object[]> findActivePositionNames();
}
