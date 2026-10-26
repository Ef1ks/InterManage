package com.intermanage.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.entity.KsefSyncState;

public interface KsefSyncStateRepository extends JpaRepository<KsefSyncState, String> {
}
