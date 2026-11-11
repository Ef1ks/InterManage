package com.intermanage.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.UserDashboardSettings;

public interface UserDashboardSettingsRepository extends JpaRepository<UserDashboardSettings, UUID> {

    @Query("select s from UserDashboardSettings s where s.user.id = :userId")
    Optional<UserDashboardSettings> findOfUser(UUID userId);
}
