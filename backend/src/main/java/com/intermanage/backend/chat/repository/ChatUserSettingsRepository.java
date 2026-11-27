package com.intermanage.backend.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatUserSettings;

public interface ChatUserSettingsRepository extends JpaRepository<ChatUserSettings, UUID> {

    /** Konta z wyłączonymi potwierdzeniami odczytu (bez wiersza = włączone). */
    @Query("select s.userId from ChatUserSettings s where s.userId in :userIds and s.readReceipts = false")
    List<UUID> findWithoutReceipts(Collection<UUID> userIds);
}
