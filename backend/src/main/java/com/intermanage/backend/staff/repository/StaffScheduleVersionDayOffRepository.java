package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleVersionDayOff;

public interface StaffScheduleVersionDayOffRepository extends JpaRepository<StaffScheduleVersionDayOff, UUID> {

    @EntityGraph(attributePaths = {"employee", "version.schedule"})
    @Query("select d from StaffScheduleVersionDayOff d where d.version.id = :versionId order by d.day")
    List<StaffScheduleVersionDayOff> findOfVersion(UUID versionId);

    /** Dni wolne z ostatnich publikacji grafików, tak jak zmiany w {@code StaffScheduleVersionEntryRepository}. */
    @EntityGraph(attributePaths = {"employee", "version.schedule"})
    @Query("""
            select d from StaffScheduleVersionDayOff d
            where d.employee.id in :employeeIds and d.day between :from and :to
                and d.version.number = (select max(v.number) from StaffScheduleVersion v
                    where v.schedule = d.version.schedule)
            order by d.day""")
    List<StaffScheduleVersionDayOff> findLatestPublished(Collection<UUID> employeeIds, LocalDate from, LocalDate to);
}
