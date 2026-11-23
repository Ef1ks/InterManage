package com.intermanage.backend.chat.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.intermanage.backend.chat.entity.ChatAttachmentKind;
import com.intermanage.backend.chat.entity.ChatKind;
import com.intermanage.backend.chat.entity.ChatMentionKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Odpowiedzi i żądania czatu. */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record Person(UUID id, String name, String avatarUrl) {
    }

    /**
     * Rozmowa na liście. {@code local}: należy do sklepu, w którym jest użytkownik (czat i grupy tego sklepu,
     * rozmowa z osobą z tego sklepu), reszta stoi niżej, w czatach niezależnych od sklepu.
     */
    public record Conversation(UUID id, ChatKind kind, UUID storeId, String storeNumber, String storeName, String name,
            Person peer, int memberCount, LastMessage lastMessage, Instant lastMessageAt, long unread,
            long unreadMentions, boolean local, boolean canManage, boolean canEdit, String logoUrl) {
    }

    /** Podgląd ostatniej wiadomości; cofnięta bez treści. */
    public record LastMessage(UUID authorId, String authorName, String preview, int attachmentCount,
            boolean retracted, Instant createdAt) {
    }

    public record Attachment(UUID id, ChatAttachmentKind kind, String fileName, String contentType, long sizeBytes) {
    }

    /**
     * Wiadomość. Cofnięta: inni dostają {@code body} puste i bez załączników („Wiadomość cofnięta”), moderator
     * (zarządzający czatem) treść, którą pokazuje na szaro.
     */
    public record Message(UUID id, UUID authorId, String authorName, String authorAvatarUrl, String body,
            Instant createdAt, boolean retracted, Instant retractedAt, String retractedByName,
            List<Attachment> attachments, List<Mention> mentions, boolean mentionsMe, boolean canRetract,
            boolean canRestore) {
    }

    /** Oznaczenie w treści: {@code @label}; {@code id} = konto albo dział (przy {@code ALL} puste). */
    public record Mention(ChatMentionKind kind, UUID id, String label) {
    }

    /** Dział do oznaczenia ({@code @dział}): ilu członków rozmowy w nim pracuje. */
    public record MentionDepartment(UUID id, String name, int memberCount) {
    }

    /** Do kiedy członek przeczytał rozmowę: z tego front liczy „wyświetlone” przy własnych wiadomościach. */
    public record Read(UUID userId, Instant lastReadAt) {
    }

    /** Rozmowa z porcją wiadomości (od najstarszej w porcji), członkami, odczytami i tym, kto teraz pisze. */
    public record Thread(Conversation conversation, List<Person> members, boolean moderator, List<Message> messages,
            boolean hasMore, List<Read> reads, List<Person> typing, List<MentionDepartment> departments,
            boolean canMentionAll) {
    }

    /**
     * Panel osób sklepu: w pracy teraz (zmiana w grafiku albo odbita karta) i pozostali z ostatnią aktywnością.
     * {@code hours}: godziny zmian i odbić są tylko dla osób z dostępem do Czasu pracy, inni widzą samo „w pracy”.
     */
    public record People(boolean hours, List<Working> working, List<Other> others) {
    }

    /** {@code start}, {@code end}, {@code since} puste bez dostępu do Czasu pracy; {@code punched} = odbita karta. */
    public record Working(UUID userId, String name, String avatarUrl, String position, LocalTime start,
            LocalTime end, LocalDateTime since, boolean punched, Instant lastSeenAt, boolean online) {
    }

    public record Other(UUID userId, String name, String avatarUrl, String rankName, Instant lastSeenAt,
            boolean online) {
    }

    public record Unread(long total) {
    }

    public record GroupRequest(@NotBlank(message = "Podaj nazwę grupy") @Size(max = 80) String name, UUID storeId,
            List<UUID> memberIds) {
    }

    /** Po wymianie albo usunięciu logo: {@code previousLogoId} do „Przywróć”. */
    public record LogoChange(Conversation conversation, UUID previousLogoId) {
    }

    public record RenameRequest(@NotBlank(message = "Podaj nazwę grupy") @Size(max = 80) String name) {
    }

    public record MembersRequest(@NotNull List<UUID> userIds) {
    }

    /** {@code mentions}: {@code user:<id>}, {@code department:<id>}, {@code all}. */
    public record TextRequest(String body, List<String> mentions) {
    }

    public record DirectRequest(@NotNull UUID userId) {
    }
}
