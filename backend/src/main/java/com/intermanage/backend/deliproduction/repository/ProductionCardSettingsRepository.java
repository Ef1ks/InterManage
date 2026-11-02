package com.intermanage.backend.deliproduction.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.deliproduction.entity.ProductionCardSettings;

public interface ProductionCardSettingsRepository extends JpaRepository<ProductionCardSettings, UUID> {
}
