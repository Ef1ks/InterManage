package com.intermanage.backend.ksef.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.ksef.entity.KsefSyncState;

public interface KsefSyncStateRepository extends JpaRepository<KsefSyncState, String> {
}
