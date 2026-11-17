package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeRequest;
import com.intermanage.backend.staff.entity.TimeRequestStatus;

public interface StaffTimeRequestRepository extends JpaRepository<StaffTimeRequest, UUID> {

    String FETCH = "select r from StaffTimeRequest r join fetch r.employee e join fetch e.position p"
            + " join fetch p.department left join fetch e.user left join fetch r.createdBy";

    /** Otwarte wnioski sklepu (czekają na kierownika albo kadry), od najstarszego. */
    @Query(FETCH + " where r.store.id = :storeId and r.status in :statuses order by r.createdAt")
    List<StaffTimeRequest> findInStore(UUID storeId, Collection<TimeRequestStatus> statuses);

    /** Wnioski sklepu z dni okresu (każdy stan), od najnowszego. */
    @Query(FETCH + " where r.store.id = :storeId and r.workDate between :from and :to order by r.createdAt desc")
    List<StaffTimeRequest> findInStore(UUID storeId, LocalDate from, LocalDate to);

    /** Wnioski pracownika (wszystkie jego wpisy), od najnowszego. */
    @Query(FETCH + " where e.id in :employeeIds order by r.createdAt desc limit 200")
    List<StaffTimeRequest> findOfEmployees(Collection<UUID> employeeIds);

    /** Otwarte wnioski pracownika z dnia: ten sam odcinek nie może mieć dwóch naraz. */
    @Query("select r from StaffTimeRequest r where r.employee.id = :employeeId and r.workDate between :from and :to"
            + " and r.status in ('PENDING', 'MANAGER_APPROVED')")
    List<StaffTimeRequest> findOpen(UUID employeeId, LocalDate from, LocalDate to);
}
