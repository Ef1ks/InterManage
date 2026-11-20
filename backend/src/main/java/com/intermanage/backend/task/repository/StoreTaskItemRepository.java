package com.intermanage.backend.task.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.task.entity.StoreTaskItem;

public interface StoreTaskItemRepository extends JpaRepository<StoreTaskItem, UUID> {

    @EntityGraph(attributePaths = {"checkedBy"})
    @Query("select i from StoreTaskItem i where i.task.id in :taskIds order by i.position")
    List<StoreTaskItem> findOfTasks(Collection<UUID> taskIds);
}
