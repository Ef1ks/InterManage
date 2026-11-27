package com.intermanage.backend.chat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.chat.entity.ChatProfile;

public interface ChatProfileRepository extends JpaRepository<ChatProfile, UUID> {
}
