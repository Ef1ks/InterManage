package com.intermanage.backend.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** Wiadomości przed chwilą {@code before}, od najnowszej. */
    @Query("""
            select m from ChatMessage m left join fetch m.author left join fetch m.retractedBy
            left join fetch m.replyTo r left join fetch r.author
            where m.conversation.id = :conversationId and m.createdAt < :before
            order by m.createdAt desc, m.id desc""")
    List<ChatMessage> findPage(UUID conversationId, Instant before, Limit limit);

    @Query("""
            select m from ChatMessage m join fetch m.conversation c left join fetch c.store
            left join fetch m.author where m.id = :id""")
    Optional<ChatMessage> findWithConversation(UUID id);
}
