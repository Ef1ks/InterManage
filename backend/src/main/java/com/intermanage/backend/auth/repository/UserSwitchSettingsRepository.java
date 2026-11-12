package com.intermanage.backend.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.auth.entity.UserSwitchSettings;

public interface UserSwitchSettingsRepository extends JpaRepository<UserSwitchSettings, UUID> {

    @Query("select s from UserSwitchSettings s where s.user.id = :userId")
    Optional<UserSwitchSettings> findOfUser(UUID userId);
}
