package com.intermanage.backend.dataentry.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.dataentry.entity.DataEntrySuggestion;

public interface DataEntrySuggestionRepository extends JpaRepository<DataEntrySuggestion, UUID> {

    @Query("select s from DataEntrySuggestion s where s.store.id = :storeId and s.id in :ids")
    List<DataEntrySuggestion> findInStore(UUID storeId, Collection<UUID> ids);
}
