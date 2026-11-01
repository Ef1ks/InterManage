package com.intermanage.backend.backup.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.backup.entity.BackupSettings;

public interface BackupSettingsRepository extends JpaRepository<BackupSettings, Short> {
}
