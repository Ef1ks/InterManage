package com.intermanage.backend.staff.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.staff.entity.StaffTimeAnomalyNotice;

public interface StaffTimeAnomalyNoticeRepository extends JpaRepository<StaffTimeAnomalyNotice, UUID> {

    @Query("select n from StaffTimeAnomalyNotice n where n.employee.id in :employeeIds and n.startAt >= :since")
    List<StaffTimeAnomalyNotice> findSince(Collection<UUID> employeeIds, LocalDateTime since);
}
