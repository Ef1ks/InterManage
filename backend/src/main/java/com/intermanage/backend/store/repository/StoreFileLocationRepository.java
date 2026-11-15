package com.intermanage.backend.store.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.store.entity.StoreFileLocation;

public interface StoreFileLocationRepository extends JpaRepository<StoreFileLocation, UUID> {

    List<StoreFileLocation> findByStoreId(UUID storeId);
}
