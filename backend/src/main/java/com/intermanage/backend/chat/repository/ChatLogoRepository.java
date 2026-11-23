package com.intermanage.backend.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.intermanage.backend.chat.entity.ChatLogo;

public interface ChatLogoRepository extends JpaRepository<ChatLogo, UUID> {

    interface Active {
        UUID getConversationId();

        UUID getId();
    }

    /** Obowiązujące logo rozmów (bez treści), do list. */
    @Query("""
            select l.conversation.id as conversationId, l.id as id from ChatLogo l
            where l.conversation.id in :conversationIds and l.deletedAt is null""")
    List<Active> findActive(Collection<UUID> conversationIds);

    @Query("select l from ChatLogo l where l.conversation.id = :conversationId and l.deletedAt is null")
    Optional<ChatLogo> findActiveOf(UUID conversationId);

    @Query("select l from ChatLogo l where l.id = :id and l.conversation.id = :conversationId")
    Optional<ChatLogo> findOf(UUID conversationId, UUID id);
}
