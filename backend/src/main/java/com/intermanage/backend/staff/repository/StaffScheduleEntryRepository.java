package com.intermanage.backend.staff.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleEntry;

public interface StaffScheduleEntryRepository extends JpaRepository<StaffScheduleEntry, UUID> {

    @EntityGraph(attributePaths = {"employee", "schedule", "schedule.store", "schedule.department"})
    @Query("""
            select e from StaffScheduleEntry e
            where e.schedule.id = :scheduleId and e.deletedAt is null order by e.workDate""")
    List<StaffScheduleEntry> findActive(UUID scheduleId);

    /**
     * Wpisy na umowie o pracę pracowników ze wszystkich sklepów: godziny i odpoczynek liczą się razem, gdzie by nie
     * pracowali. Bez godzin zlecenia, które mają osobny wiersz ({@link #findCivilOfEmployees}).
     */
    @EntityGraph(attributePaths = {"employee", "schedule", "schedule.store", "schedule.department"})
    @Query("""
            select e from StaffScheduleEntry e
            where e.employee.id in :employeeIds and e.workDate between :from and :to and e.deletedAt is null
                and e.civil = false
            order by e.workDate""")
    List<StaffScheduleEntry> findOfEmployees(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    /** Godziny na umowie zlecenia u tego samego pracodawcy, ze wszystkich sklepów. */
    @EntityGraph(attributePaths = {"employee", "schedule", "schedule.store", "schedule.department"})
    @Query("""
            select e from StaffScheduleEntry e
            where e.employee.id in :employeeIds and e.workDate between :from and :to and e.deletedAt is null
                and e.civil = true
            order by e.workDate""")
    List<StaffScheduleEntry> findCivilOfEmployees(Collection<UUID> employeeIds, LocalDate from, LocalDate to);

    /** Pracownicy z wpisem w tym grafiku, także spoza działu i sklepu: stoją w nim jako goście. */
    @Query("select distinct e.employee.id from StaffScheduleEntry e where e.schedule.id = :scheduleId and e.deletedAt is null")
    List<UUID> findEmployeeIds(UUID scheduleId);

    /** Zmiany w grafikach sklepu (także gości z innych sklepów) do pulpitu: kto ma teraz być w pracy. */
    @EntityGraph(attributePaths = {"employee", "employee.position", "schedule", "schedule.store"})
    @Query("""
            select e from StaffScheduleEntry e
            where e.schedule.store.id = :storeId and e.workDate between :from and :to and e.deletedAt is null
                and e.civil = false""")
    List<StaffScheduleEntry> findInStore(UUID storeId, LocalDate from, LocalDate to);
}
