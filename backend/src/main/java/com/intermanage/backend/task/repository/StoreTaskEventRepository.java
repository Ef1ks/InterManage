package com.intermanage.backend.task.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.task.entity.StoreTaskEvent;

public interface StoreTaskEventRepository extends JpaRepository<StoreTaskEvent, UUID> {

    @EntityGraph(attributePaths = {"user"})
    @Query("select e from StoreTaskEvent e where e.task.id = :taskId order by e.at")
    List<StoreTaskEvent> findOfTask(UUID taskId);
}
