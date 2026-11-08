package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleVersionEntry;

public interface StaffScheduleVersionEntryRepository extends JpaRepository<StaffScheduleVersionEntry, UUID> {

    @EntityGraph(attributePaths = {"employee", "version.schedule", "version.schedule.store"})
    @Query("select e from StaffScheduleVersionEntry e where e.version.id = :versionId order by e.workDate")
    List<StaffScheduleVersionEntry> findEntries(UUID versionId);

    /** Co pracownicy widzą w miesiącu we wszystkich sklepach: wpisy z ostatnich publikacji grafików. */
    @EntityGraph(attributePaths = {"employee", "version.schedule", "version.schedule.store"})
    @Query("""
            select e from StaffScheduleVersionEntry e
            where e.employee.id in :employeeIds and e.workDate between :from and :to and e.civil = false
                and e.version.number = (select max(v.number) from StaffScheduleVersion v
                    where v.schedule = e.version.schedule)
            order by e.workDate""")
    List<StaffScheduleVersionEntry> findLatestPublished(Collection<UUID> employeeIds, LocalDate from, LocalDate to);
}
