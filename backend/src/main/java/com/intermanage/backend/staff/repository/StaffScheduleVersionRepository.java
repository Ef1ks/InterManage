package com.intermanage.backend.staff.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffScheduleVersion;

public interface StaffScheduleVersionRepository extends JpaRepository<StaffScheduleVersion, UUID> {

    @EntityGraph(attributePaths = "publishedBy")
    @Query("select v from StaffScheduleVersion v where v.schedule.id = :scheduleId order by v.number")
    List<StaffScheduleVersion> findOfSchedule(UUID scheduleId);
}
