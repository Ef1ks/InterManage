package com.intermanage.backend.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatMute;

public interface ChatMuteRepository extends JpaRepository<ChatMute, UUID> {

    @Query("select m from ChatMute m where m.userId = :userId and m.removedAt is null order by m.createdAt")
    List<ChatMute> findActive(UUID userId);

    @Query("""
            select m from ChatMute m
            where m.userId = :userId and m.kind = :kind and m.targetId = :targetId and m.removedAt is null""")
    Optional<ChatMute> findActive(UUID userId, ChatMute.Kind kind, UUID targetId);
}
