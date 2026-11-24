package com.intermanage.backend.cadtop.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.cadtop.entity.CadStoreRegion;

public interface CadStoreRegionRepository extends JpaRepository<CadStoreRegion, UUID> {
}
