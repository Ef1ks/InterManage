package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffOpenHoliday;

public interface StaffOpenHolidayRepository extends JpaRepository<StaffOpenHoliday, UUID> {

    @Query("""
            select h from StaffOpenHoliday h join fetch h.store
            where h.store.id in :storeIds and h.day between :from and :to and h.deletedAt is null""")
    List<StaffOpenHoliday> findActive(Collection<UUID> storeIds, LocalDate from, LocalDate to);
}
