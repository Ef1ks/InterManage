package com.intermanage.backend.chat.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.component.UserImages;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.PermissionCategory;
import com.intermanage.backend.auth.entity.PermissionLevel;
import com.intermanage.backend.auth.entity.StoreRole;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.UserAvatarRepository;
import com.intermanage.backend.chat.component.ChatTyping;
import com.intermanage.backend.chat.dto.ChatDtos;
import com.intermanage.backend.chat.entity.ChatAttachment;
import com.intermanage.backend.chat.entity.ChatConversation;
import com.intermanage.backend.chat.entity.ChatKind;
import com.intermanage.backend.chat.entity.ChatLogo;
import com.intermanage.backend.chat.entity.ChatMember;
import com.intermanage.backend.chat.entity.ChatMention;
import com.intermanage.backend.chat.entity.ChatMentionKind;
import com.intermanage.backend.chat.entity.ChatMessage;
import com.intermanage.backend.chat.entity.ChatMute;
import com.intermanage.backend.chat.entity.ChatProfile;
import com.intermanage.backend.chat.entity.ChatUserSettings;
import com.intermanage.backend.chat.mapper.ChatMapper;
import com.intermanage.backend.chat.repository.ChatAttachmentRepository;
import com.intermanage.backend.chat.repository.ChatConversationRepository;
import com.intermanage.backend.chat.repository.ChatMemberRepository;
import com.intermanage.backend.chat.repository.ChatLogoRepository;
import com.intermanage.backend.chat.repository.ChatMentionRepository;
import com.intermanage.backend.chat.repository.ChatMessageRepository;
import com.intermanage.backend.chat.repository.ChatMuteRepository;
import com.intermanage.backend.chat.repository.ChatProfileRepository;
import com.intermanage.backend.chat.repository.ChatUserSettingsRepository;
import com.intermanage.backend.common.component.LiveEvents;
import com.intermanage.backend.common.component.ScanFiles;
import com.intermanage.backend.common.exception.ApiException;
import com.intermanage.backend.staff.entity.StaffDepartment;
import com.intermanage.backend.staff.entity.StaffEmployee;
import com.intermanage.backend.staff.repository.StaffEmployeeRepository;
import com.intermanage.backend.staff.service.StaffTimeService;
import com.intermanage.backend.store.entity.Store;
import com.intermanage.backend.store.repository.StoreRepository;

/**
 * Czat firmowy. Każdy sklep ma czat całego sklepu (członkami są wszyscy z dostępem do sklepu), do tego grupy w sklepie
 * albo niezależne od sklepu i rozmowy dwóch osób. Pisze każdy, kto widzi rozmowę; grupy zakłada i ich członków zmienia
 * zarządzający czatem ({@code CHAT:EDIT}: domyślnie Aderon, Dyrektor, Kierownik i jego zastępca, w sklepie grupy;
 * przy grupie niezależnej także jej założyciel), on też widzi treść cofniętych wiadomości.
 *
 * <p>Wiadomość cofa się tylko dla wszystkich (znacznik): inni widzą „Wiadomość cofnięta”, moderator treść na szaro.
 * Zmiany idą na żywo do członków ({@link LiveEvents#publishToUsers}, temat {@code chat}).
 */
@Service
public class ChatService {

    public static final int PAGE_SIZE = 50;
    public static final int MAX_BODY = 4000;
    public static final int MAX_FILES = 10;
    /** Oznaczeń w jednej wiadomości (osoby, działy, wszyscy). */
    public static final int MAX_MENTIONS = 50;
    /** Logo czatu: mały obrazek, przeglądarka i tak go zmniejsza. */
    public static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    /**
     * Pliki jednej wiadomości razem. Idą jednym żądaniem, a łącze sklepu ma ok. 200 kB/s: 10 MB to ok. 50 s, poniżej
     * 100 s, po których Cloudflare zrywa żądanie.
     */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** Konto jest „online”, gdy zgłosiło się w tym czasie (przeglądarka i telefon co minutę). */
    public static final Duration ONLINE = Duration.ofMinutes(2);

    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");
    private static final int PREVIEW = 140;

    private final ChatConversationRepository conversations;
    private final ChatMemberRepository members;
    private final ChatMessageRepository messages;
    private final ChatAttachmentRepository attachments;
    private final AppUserRepository users;
    private final StoreRepository stores;
    private final UserAvatarRepository avatars;
    private final UserImages images;
    private final StaffTimeService staffTime;
    private final ChatTyping typing;
    private final LiveEvents live;
    private final ChatMapper mapper;
    private final ChatMentionRepository mentions;
    private final StaffEmployeeRepository employees;
    private final ChatLogoRepository logos;
    private final ChatMuteRepository mutes;
    private final ChatUserSettingsRepository chatSettings;
    private final ChatProfileRepository profiles;

    public ChatService(ChatConversationRepository conversations, ChatMemberRepository members,
            ChatMessageRepository messages, ChatAttachmentRepository attachments, AppUserRepository users,
            StoreRepository stores, UserAvatarRepository avatars, UserImages images, StaffTimeService staffTime,
            ChatTyping typing, LiveEvents live, ChatMapper mapper, ChatMentionRepository mentions,
            StaffEmployeeRepository employees, ChatLogoRepository logos, ChatMuteRepository mutes,
            ChatUserSettingsRepository chatSettings, ChatProfileRepository profiles) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.attachments = attachments;
        this.users = users;
        this.stores = stores;
        this.avatars = avatars;
        this.images = images;
        this.staffTime = staffTime;
        this.typing = typing;
        this.live = live;
        this.mapper = mapper;
        this.mentions = mentions;
        this.employees = employees;
        this.logos = logos;
        this.mutes = mutes;
        this.chatSettings = chatSettings;
        this.profiles = profiles;
    }

    public record StoredFile(String fileName, String contentType, byte[] content) {
    }

    // ---------------------------------------------------------------- rozmowy

    /** Wszystkie rozmowy konta od najświeższej; {@code currentStoreId} mówi, które należą do bieżącego sklepu. */
    @Transactional
    public List<ChatDtos.Conversation> conversations(UUID userId, UUID currentStoreId) {
        AppUser me = user(userId);
        return summaries(visible(me), me, currentStoreId);
    }

    @Transactional
    public ChatDtos.Unread unread(UUID userId) {
        AppUser me = user(userId);
        // Wyciszona rozmowa nie liczy się do licznika (jak w WhatsAppie); na liście rozmów dalej ma swój.
        Set<UUID> muted = mutedTargets(userId, ChatMute.Kind.CONVERSATION);
        List<UUID> ids = visible(me).stream().map(ChatConversation::getId).filter(id -> !muted.contains(id)).toList();
        long total = ids.isEmpty() ? 0
                : conversations.countUnread(ids, userId).stream()
                        .mapToLong(ChatConversationRepository.Unread::getUnread).sum();
        return new ChatDtos.Unread(total);
    }

    @Transactional
    public ChatDtos.Conversation createGroup(UUID userId, ChatDtos.GroupRequest request) {
        AppUser me = user(userId);
        Store store = request.storeId() == null ? null
                : myStores(me).stream().filter(s -> s.getId().equals(request.storeId())).findFirst()
                    .orElseThrow(() -> ApiException.forbidden("Nie masz dostępu do tego sklepu"));
        if (store != null && !chatEdit(me, store.getId())) {
            throw ApiException.forbidden("Grupy w sklepie zakłada osoba z uprawnieniem do zarządzania czatem");
        }
        if (store == null && !anyChatEdit(me)) {
            throw ApiException.forbidden("Grupy zakłada osoba z uprawnieniem do zarządzania czatem");
        }
        Set<UUID> wanted = new LinkedHashSet<>();
        wanted.add(userId);
        if (request.memberIds() != null) {
            wanted.addAll(request.memberIds());
        }
        Map<UUID, AppUser> allowed = byId(candidates(me, store));
        allowed.put(userId, me);
        List<AppUser> chosen = wanted.stream().map(id -> {
            AppUser user = allowed.get(id);
            if (user == null) {
                throw ApiException.badRequest(store == null ? "Do grupy dodasz tylko osoby z Twoich sklepów"
                        : "Do grupy sklepu dodasz tylko osoby z dostępem do tego sklepu");
            }
            return user;
        }).toList();
        ChatConversation conversation = conversations.save(ChatConversation.group(store, request.name().strip(), me));
        chosen.forEach(user -> members.save(new ChatMember(conversation, user, me)));
        chosen.forEach(user -> conversations.markRead(conversation.getId(), user.getId(), Instant.now()));
        announce(conversation, ids(chosen), "conversation", Map.of());
        return summaries(List.of(conversation), me, request.storeId()).getFirst();
    }

    @Transactional
    public ChatDtos.Conversation rename(UUID userId, UUID conversationId, ChatDtos.RenameRequest request) {
        AppUser me = user(userId);
        ChatConversation conversation = editable(me, conversationId);
        conversation.rename(request.name().strip());
        announce(conversation, audience(conversation), "conversation", Map.of());
        return summaries(List.of(conversation), me, storeId(conversation)).getFirst();
    }

    /** Zastępuje członków grupy: brakujący dostają znacznik usunięcia, nowi wchodzą od teraz (bez starych nowości). */
    @Transactional
    public ChatDtos.Conversation setMembers(UUID userId, UUID conversationId, ChatDtos.MembersRequest request) {
        AppUser me = user(userId);
        ChatConversation conversation = managed(me, conversationId);
        Set<UUID> wanted = new LinkedHashSet<>(request.userIds());
        if (wanted.isEmpty()) {
            throw ApiException.badRequest("Grupa musi mieć choć jednego członka");
        }
        List<ChatMember> current = members.findActive(List.of(conversationId));
        Set<UUID> before = current.stream().map(member -> member.getUser().getId())
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, AppUser> allowed = byId(candidates(me, conversation.getStore()));
        allowed.put(userId, me);
        for (UUID id : wanted) {
            if (!before.contains(id) && !allowed.containsKey(id)) {
                throw ApiException.badRequest("Tej osoby nie dodasz do grupy: nie ma dostępu do sklepu grupy");
            }
        }
        current.stream().filter(member -> !wanted.contains(member.getUser().getId()))
                .forEach(member -> member.remove(me));
        wanted.stream().filter(id -> !before.contains(id)).forEach(id -> {
            members.save(new ChatMember(conversation, allowed.get(id), me));
            conversations.markRead(conversationId, id, Instant.now());
        });
        Set<UUID> told = new HashSet<>(before);
        told.addAll(wanted);
        announce(conversation, told, "conversation", Map.of());
        return summaries(List.of(conversation), me, storeId(conversation)).getFirst();
    }

    /** Rozmowa dwóch osób: istniejąca albo nowa. Druga osoba musi mieć dostęp do któregoś z moich sklepów. */
    @Transactional
    public ChatDtos.Conversation direct(UUID userId, UUID otherId, UUID currentStoreId) {
        if (userId.equals(otherId)) {
            throw ApiException.badRequest("Wybierz inną osobę");
        }
        AppUser me = user(userId);
        AppUser other = byId(candidates(me, null)).get(otherId);
        if (other == null) {
            throw ApiException.badRequest("Z tą osobą nie porozmawiasz: nie pracuje w żadnym z Twoich sklepów");
        }
        String key = ChatConversation.directKey(userId, otherId);
        conversations.lockDirect("chat-direct:" + key);
        ChatConversation conversation = conversations.findDirect(key)
                .orElseGet(() -> {
                    ChatConversation created = conversations.save(ChatConversation.direct(userId, otherId, me));
                    members.save(new ChatMember(created, me, me));
                    members.save(new ChatMember(created, other, me));
                    conversations.markRead(created.getId(), userId, Instant.now());
                    conversations.markRead(created.getId(), otherId, Instant.now());
                    return created;
                });
        return summaries(List.of(conversation), me, currentStoreId).getFirst();
    }

    /** Osoby do nowej rozmowy albo grupy: ze sklepu ({@code wholeCompany} = ze wszystkich moich sklepów), bez mnie. */
    @Transactional(readOnly = true)
    public List<ChatDtos.Person> candidates(UUID userId, UUID storeId, boolean wholeCompany) {
        AppUser me = user(userId);
        Store store = wholeCompany ? null : stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Nie ma takiego sklepu"));
        List<AppUser> list = candidates(me, store).stream().filter(user -> !user.getId().equals(userId)).toList();
        Map<UUID, String> photos = avatarUrls(ids(list));
        return list.stream().map(user -> mapper.toPerson(user, photos.get(user.getId()))).toList();
    }

    // ---------------------------------------------------------------- wiadomości

    /** Porcja wiadomości przed {@code before} (bez niej najnowsze), od najstarszej w porcji. */
    @Transactional
    public ChatDtos.Thread thread(UUID userId, UUID conversationId, Instant before) {
        AppUser me = user(userId);
        ChatConversation conversation = visible(me, conversationId);
        conversations.startReading(List.of(conversationId), userId, Instant.now());
        List<ChatMessage> page = new ArrayList<>(messages.findPage(conversationId,
                before == null ? FAR_FUTURE : before, Limit.of(PAGE_SIZE + 1)));
        boolean hasMore = page.size() > PAGE_SIZE;
        if (hasMore) {
            page = new ArrayList<>(page.subList(0, PAGE_SIZE));
        }
        page = page.reversed();
        boolean moderator = moderates(conversation, me);

        List<AppUser> people = memberUsers(conversation);
        Set<UUID> photoIds = new HashSet<>(ids(people));
        page.forEach(message -> {
            if (message.getAuthor() != null) {
                photoIds.add(message.getAuthor().getId());
            }
        });
        Map<UUID, String> photos = avatarUrls(photoIds);
        List<ChatDtos.Message> list = toMessages(page, me, moderator, photos);
        Map<UUID, AppUser> byId = byId(people);
        List<ChatDtos.Person> typingNow = typing.typingNow(conversationId, userId).stream().map(byId::get)
                .filter(Objects::nonNull).map(user -> mapper.toPerson(user, photos.get(user.getId()))).toList();
        List<ChatDtos.Read> reads = reads(conversationId, userId);
        ChatDtos.Conversation summary = summaries(List.of(conversation), me, storeId(conversation)).getFirst();
        List<ChatDtos.Person> memberList = people.stream().map(user -> mapper.toPerson(user, photos.get(user.getId())))
                .toList();
        List<ChatDtos.MentionDepartment> departmentList = departments(people).values().stream()
                .map(department -> new ChatDtos.MentionDepartment(department.id(), department.name(),
                        department.userIds().size()))
                .sorted(Comparator.comparing(department -> department.name().toLowerCase()))
                .toList();
        return new ChatDtos.Thread(summary, memberList, moderator, list, hasMore, reads, typingNow, departmentList,
                canMentionAll(conversation, me));
    }

    @Transactional
    public ChatDtos.Message send(UUID userId, UUID conversationId, String body, List<MultipartFile> files,
            List<String> mentionTokens, UUID replyToId) {
        AppUser me = user(userId);
        ChatConversation conversation = visible(me, conversationId);
        ChatMessage replyTo = replyToId == null ? null : messages.findWithConversation(replyToId)
                .filter(found -> found.getConversation().getId().equals(conversationId))
                .orElseThrow(() -> ApiException.badRequest("Odpowiadasz tylko na wiadomość z tej rozmowy"));
        List<AppUser> people = memberUsers(conversation);
        List<Resolved> resolved = resolveMentions(conversation, me, people, mentionTokens);
        String text = body == null ? "" : body.strip();
        List<MultipartFile> parts = files == null ? List.of() : files.stream().filter(file -> !file.isEmpty()).toList();
        if (text.isEmpty() && parts.isEmpty()) {
            throw ApiException.badRequest("Napisz wiadomość albo dołącz plik");
        }
        if (text.length() > MAX_BODY) {
            throw ApiException.badRequest("Wiadomość może mieć najwyżej " + MAX_BODY + " znaków");
        }
        if (parts.size() > MAX_FILES) {
            throw ApiException.badRequest("Do jednej wiadomości dołączysz najwyżej " + MAX_FILES + " plików");
        }
        if (parts.stream().mapToLong(MultipartFile::getSize).sum() > MAX_FILE_BYTES) {
            throw ApiException.badRequest("Pliki jednej wiadomości mogą mieć razem najwyżej 10 MB");
        }
        ChatMessage message = messages.save(new ChatMessage(conversation, me, text, replyTo));
        for (MultipartFile file : parts) {
            String type = file.getContentType() == null || file.getContentType().isBlank()
                    ? "application/octet-stream" : file.getContentType();
            try {
                attachments.save(ChatAttachment.file(message, fileName(file), type, file.getBytes()));
            } catch (IOException e) {
                throw ApiException.badRequest("Nie udało się odczytać pliku " + fileName(file));
            }
        }
        Set<UUID> mentioned = new LinkedHashSet<>();
        for (Resolved mention : resolved) {
            mentions.save(new ChatMention(message, mention.kind(), mention.targetId(), mention.label()));
            mentioned.addAll(mention.recipients());
        }
        mentioned.remove(userId);
        if (!mentioned.isEmpty()) {
            mentions.addRecipients(message.getId(), mentioned);
        }
        conversation.touch(message.getCreatedAt());
        Set<UUID> audience = new HashSet<>(ids(people));
        conversations.startReadingAt(conversationId, audience, message.getCreatedAt().minusMillis(1));
        conversations.markRead(conversationId, userId, message.getCreatedAt());
        conversations.touchPresence(userId);
        typing.stopped(conversationId, userId);
        // Z treścią do powiadomienia w przeglądarce (toast, powiadomienie systemowe): idzie tylko do członków rozmowy.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("messageId", message.getId());
        fields.put("authorId", userId);
        fields.put("authorName", me.getDisplayName());
        fields.put("mentioned", List.copyOf(mentioned));
        fields.put("preview", notificationPreview(text, parts.size()));
        fields.put("kind", conversation.getKind().name());
        String title = notificationTitle(conversation);
        if (title != null) {
            fields.put("title", title);
        }
        if (replyTo != null && replyTo.getAuthor() != null) {
            fields.put("replyToAuthorId", replyTo.getAuthor().getId());
        }
        announce(conversation, audience, "message", fields);
        messages.flush();
        boolean moderator = moderates(conversation, me);
        return toMessages(List.of(message), me, moderator, avatarUrls(List.of(userId))).getFirst();
    }

    /** Cofnięcie dla wszystkich: tylko autor (zarządzający czatem też nie cofa cudzych). */
    @Transactional
    public ChatDtos.Message retract(UUID userId, UUID messageId) {
        AppUser me = user(userId);
        ChatMessage message = message(me, messageId);
        boolean moderator = moderates(message.getConversation(), me);
        if (message.getRetractedAt() != null) {
            throw ApiException.conflict("Wiadomość jest już cofnięta");
        }
        if (!mine(message, me)) {
            throw ApiException.forbidden("Cofasz tylko swoje wiadomości");
        }
        message.retract(me);
        announce(message.getConversation(), audience(message.getConversation()), "retracted",
                Map.of("messageId", messageId));
        return toMessages(List.of(message), me, moderator, avatarUrls(authorIds(message))).getFirst();
    }

    /** Przywrócenie cofniętej: tylko autor, który sam ją cofnął. */
    @Transactional
    public ChatDtos.Message restore(UUID userId, UUID messageId) {
        AppUser me = user(userId);
        ChatMessage message = message(me, messageId);
        boolean moderator = moderates(message.getConversation(), me);
        if (message.getRetractedAt() == null) {
            throw ApiException.conflict("Wiadomość nie jest cofnięta");
        }
        if (!canRestore(message, me)) {
            throw ApiException.forbidden("Przywracasz tylko swoje wiadomości");
        }
        message.restore();
        announce(message.getConversation(), audience(message.getConversation()), "restored",
                Map.of("messageId", messageId));
        return toMessages(List.of(message), me, moderator, avatarUrls(authorIds(message))).getFirst();
    }

    @Transactional
    public void markRead(UUID userId, UUID conversationId) {
        AppUser me = user(userId);
        ChatConversation conversation = visible(me, conversationId);
        conversations.markRead(conversationId, userId, Instant.now());
        // Bez potwierdzeń odczytu inni nie dowiadują się o odczycie; własne karty odświeżają licznik.
        Set<UUID> told = receiptsOn(userId) ? audience(conversation) : Set.of(userId);
        announce(conversation, told, "read", Map.of("userId", userId));
    }

    // ---------------------------------------------------------------- profile

    /**
     * Profil osoby, z którą mam wspólny sklep (albo mój). Ranga z bieżącego sklepu, a gdy jej tam nie ma, z pierwszego
     * wspólnego sklepu, w którym ją ma.
     */
    @Transactional(readOnly = true)
    public ChatDtos.Profile profile(UUID userId, UUID otherId, UUID currentStoreId) {
        AppUser me = user(userId);
        AppUser other = userId.equals(otherId) ? me : byId(candidates(me, null)).get(otherId);
        if (other == null) {
            throw ApiException.notFound("Nie ma takiej osoby w Twoich sklepach");
        }
        ChatProfile own = profiles.findById(otherId).orElse(null);
        String rank = other.rankNameIn(currentStoreId);
        if (rank == null) {
            rank = myStores(me).stream().map(store -> other.rankNameIn(store.getId())).filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }
        String position = employees.findActiveOfUsers(List.of(otherId)).stream()
                .map(employee -> employee.getPosition().getName()).findFirst().orElse(null);
        Instant lastSeen = conversations.findSeen(List.of(otherId)).stream()
                .map(ChatConversationRepository.Seen::getLastSeenAt).findFirst().orElse(null);
        return new ChatDtos.Profile(other.getId(), other.getDisplayName(), avatarUrls(List.of(otherId)).get(otherId),
                own == null ? null : own.getNickname(), own == null ? null : own.getAbout(),
                own == null ? null : own.getPhone(), rank, position, lastSeen,
                lastSeen != null && lastSeen.isAfter(Instant.now().minus(ONLINE)), userId.equals(otherId));
    }

    @Transactional
    public ChatDtos.Profile saveProfile(UUID userId, ChatDtos.ProfileRequest request, UUID currentStoreId) {
        String phone = blankToNull(request.phone());
        if (phone != null && (!phone.matches("[0-9+()\\-. ]+") || phone.replaceAll("\\D", "").length() < 6)) {
            throw ApiException.badRequest("Numer telefonu: same cyfry, spacje, „+” i „-”, co najmniej 6 cyfr");
        }
        ChatProfile own = profiles.findById(userId).orElseGet(() -> new ChatProfile(userId));
        own.change(blankToNull(request.nickname()), blankToNull(request.about()), phone);
        profiles.save(own);
        return profile(userId, userId, currentStoreId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ---------------------------------------------------------------- ustawienia i wyciszenia

    @Transactional(readOnly = true)
    public ChatDtos.Settings settings(UUID userId) {
        ChatUserSettings own = chatSettings.findById(userId).orElseGet(() -> new ChatUserSettings(userId));
        List<ChatMute> active = mutes.findActive(userId);
        List<UUID> userIds = active.stream().filter(mute -> mute.getKind() == ChatMute.Kind.USER)
                .map(ChatMute::getTargetId).toList();
        Map<UUID, String> photos = avatarUrls(userIds);
        List<ChatDtos.Person> mutedUsers = users.findAllById(userIds).stream()
                .map(user -> mapper.toPerson(user, photos.get(user.getId())))
                .sorted(Comparator.comparing(person -> person.name().toLowerCase())).toList();
        List<UUID> mutedConversations = active.stream().filter(mute -> mute.getKind() == ChatMute.Kind.CONVERSATION)
                .map(ChatMute::getTargetId).toList();
        return new ChatDtos.Settings(own.isReadReceipts(), own.getSound(), mutedUsers, mutedConversations);
    }

    @Transactional
    public ChatDtos.Settings saveSettings(UUID userId, ChatDtos.SettingsRequest request) {
        ChatUserSettings own = chatSettings.findById(userId).orElseGet(() -> new ChatUserSettings(userId));
        boolean receiptsChanged = own.isReadReceipts() != request.readReceipts();
        own.change(request.readReceipts(), request.sound());
        chatSettings.save(own);
        if (receiptsChanged) {
            // „Wyświetlone” zmienia się w każdej rozmowie konta, więc członkowie odświeżają odczyty.
            AppUser me = user(userId);
            for (ChatConversation conversation : visible(me)) {
                announce(conversation, audience(conversation), "read", Map.of("userId", userId));
            }
        }
        return settings(userId);
    }

    /** Wyciszenie osoby: jej wiadomości nie dają powiadomień ani dźwięku (dalej są w rozmowach). */
    @Transactional
    public ChatDtos.Settings muteUser(UUID userId, UUID otherId, boolean muted) {
        if (userId.equals(otherId)) {
            throw ApiException.badRequest("Nie wyciszysz siebie");
        }
        users.findById(otherId).orElseThrow(() -> ApiException.notFound("Nie ma takiego konta"));
        setMute(userId, ChatMute.Kind.USER, otherId, muted);
        return settings(userId);
    }

    /** Wyciszenie rozmowy: bez powiadomień i poza licznikiem; oznaczenie mnie dalej powiadamia. */
    @Transactional
    public ChatDtos.Settings muteConversation(UUID userId, UUID conversationId, boolean muted) {
        visible(user(userId), conversationId);
        setMute(userId, ChatMute.Kind.CONVERSATION, conversationId, muted);
        live.publishToUsers(List.of(userId), "chat", Map.of("type", "conversation", "conversationId", conversationId));
        return settings(userId);
    }

    private void setMute(UUID userId, ChatMute.Kind kind, UUID targetId, boolean muted) {
        var current = mutes.findActive(userId, kind, targetId);
        if (muted && current.isEmpty()) {
            mutes.save(new ChatMute(userId, kind, targetId));
        } else if (!muted) {
            current.ifPresent(ChatMute::remove);
        }
    }

    private Set<UUID> mutedTargets(UUID userId, ChatMute.Kind kind) {
        return mutes.findActive(userId).stream().filter(mute -> mute.getKind() == kind).map(ChatMute::getTargetId)
                .collect(Collectors.toSet());
    }

    private boolean receiptsOn(UUID userId) {
        return chatSettings.findById(userId).map(ChatUserSettings::isReadReceipts).orElse(true);
    }

    /**
     * Odczyty rozmowy. Potwierdzenia działają w obie strony: konto z wyłączonymi widzi tylko własny odczyt, a odczytów
     * kont z wyłączonymi nie widzi nikt.
     */
    private List<ChatDtos.Read> reads(UUID conversationId, UUID userId) {
        List<ChatConversationRepository.ReadState> all = conversations.findReads(conversationId);
        boolean mineOn = receiptsOn(userId);
        Set<UUID> hidden = all.isEmpty() ? Set.of()
                : new HashSet<>(chatSettings.findWithoutReceipts(all.stream()
                        .map(ChatConversationRepository.ReadState::getUserId).toList()));
        return all.stream()
                .filter(read -> read.getUserId().equals(userId) || (mineOn && !hidden.contains(read.getUserId())))
                .map(read -> new ChatDtos.Read(read.getUserId(), read.getLastReadAt())).toList();
    }

    private static String notificationPreview(String text, int files) {
        if (!text.isEmpty()) {
            return text.length() > PREVIEW ? text.substring(0, PREVIEW) + "…" : text;
        }
        return files == 1 ? "Plik" : "Pliki: " + files;
    }

    /** Nazwa rozmowy w powiadomieniu; rozmowa dwóch osób jej nie ma (tytułem jest autor). */
    private static String notificationTitle(ChatConversation conversation) {
        return switch (conversation.getKind()) {
            case DIRECT -> null;
            case STORE -> conversation.getName() != null ? conversation.getName()
                    : "Cały sklep " + conversation.getStore().getNumber();
            case GROUP -> conversation.getName();
        };
    }

    @Transactional(readOnly = true)
    public void typing(UUID userId, UUID conversationId) {
        AppUser me = user(userId);
        ChatConversation conversation = visible(me, conversationId);
        if (!typing.typing(conversationId, userId)) {
            return;
        }
        Set<UUID> others = new HashSet<>(audience(conversation));
        others.remove(userId);
        announce(conversation, others, "typing", Map.of("userId", userId, "name", me.getDisplayName()));
    }

    @Transactional(readOnly = true)
    public StoredFile attachment(UUID userId, UUID attachmentId) {
        AppUser me = user(userId);
        ChatAttachment attachment = attachments.findWithMessage(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego pliku"));
        ChatMessage message = attachment.getMessage();
        visible(me, message.getConversation().getId());
        if (message.getRetractedAt() != null && !moderates(message.getConversation(), me)) {
            throw ApiException.notFound("Wiadomość została cofnięta");
        }
        if (attachment.getContent() == null) {
            throw ApiException.notFound("Plik wygasł");
        }
        return new StoredFile(attachment.getFileName(), attachment.getContentType(), attachment.getContent());
    }

    // ---------------------------------------------------------------- oznaczenia

    /** Oznaczenie gotowe do zapisu: kogo oznacza (członkowie rozmowy). */
    private record Resolved(ChatMentionKind kind, UUID targetId, String label, Set<UUID> recipients) {
    }

    /** Dział z członkami rozmowy, którzy w nim pracują (stałe stanowisko w Pracownikach). */
    private record MentionTarget(UUID id, String name, Set<UUID> userIds) {
    }

    /**
     * Oznaczenia z żądania: {@code user:<id>} (członek rozmowy), {@code department:<id>} (dział, w którym pracuje
     * któryś z członków), {@code all}. Nazwę do treści bierze serwer, nie przeglądarka.
     */
    private List<Resolved> resolveMentions(ChatConversation conversation, AppUser me, List<AppUser> people,
            List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }
        if (tokens.size() > MAX_MENTIONS) {
            throw ApiException.badRequest("W jednej wiadomości oznaczysz najwyżej " + MAX_MENTIONS + " osób i działów");
        }
        Map<UUID, AppUser> byId = byId(people);
        Map<UUID, MentionTarget> departments = null;
        Map<String, Resolved> result = new LinkedHashMap<>();
        for (String raw : tokens) {
            String token = raw == null ? "" : raw.strip();
            if (token.equals("all")) {
                if (!canMentionAll(conversation, me)) {
                    throw ApiException.forbidden("„@wszyscy” w czacie całego sklepu oznacza zarządzający czatem");
                }
                result.putIfAbsent(token, new Resolved(ChatMentionKind.ALL, null, "wszyscy",
                        new HashSet<>(byId.keySet())));
            } else if (token.startsWith("user:")) {
                AppUser user = byId.get(uuid(token.substring("user:".length())));
                if (user == null) {
                    throw ApiException.badRequest("Oznaczysz tylko osobę z tej rozmowy");
                }
                result.putIfAbsent(token, new Resolved(ChatMentionKind.USER, user.getId(), user.getDisplayName(),
                        Set.of(user.getId())));
            } else if (token.startsWith("department:")) {
                if (departments == null) {
                    departments = departments(people);
                }
                MentionTarget department = departments.get(uuid(token.substring("department:".length())));
                if (department == null) {
                    throw ApiException.badRequest("Oznaczysz tylko dział, w którym pracuje ktoś z tej rozmowy");
                }
                result.putIfAbsent(token, new Resolved(ChatMentionKind.DEPARTMENT, department.id(), department.name(),
                        department.userIds()));
            } else {
                throw ApiException.badRequest("Nieznane oznaczenie: " + token);
            }
        }
        return List.copyOf(result.values());
    }

    /** {@code @wszyscy}: w grupie i rozmowie każdy, w czacie całego sklepu tylko zarządzający czatem. */
    private boolean canMentionAll(ChatConversation conversation, AppUser me) {
        return conversation.getKind() != ChatKind.STORE || moderates(conversation, me);
    }

    /** Działy członków rozmowy (po stałym stanowisku aktywnego wpisu pracownika). */
    private Map<UUID, MentionTarget> departments(List<AppUser> people) {
        if (people.isEmpty()) {
            return Map.of();
        }
        Map<UUID, MentionTarget> result = new LinkedHashMap<>();
        for (StaffEmployee employee : employees.findActiveOfUsers(ids(people))) {
            StaffDepartment department = employee.getPosition().getDepartment();
            if (department.getDeletedAt() != null) {
                continue;
            }
            result.computeIfAbsent(department.getId(),
                    id -> new MentionTarget(id, department.getName(), new LinkedHashSet<>()))
                    .userIds().add(employee.getUser().getId());
        }
        return result;
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Nieznane oznaczenie");
        }
    }

    // ---------------------------------------------------------------- obecność

    @Transactional
    public void touchPresence(UUID userId) {
        conversations.touchPresence(userId);
    }

    /** Panel osób sklepu: w pracy (zmiana w grafiku teraz albo odbita karta), niżej reszta z ostatnią aktywnością. */
    @Transactional(readOnly = true)
    public ChatDtos.People people(UUID storeId, boolean hours) {
        List<AppUser> list = members.findUsersOfStores(List.of(storeId));
        Map<UUID, AppUser> byId = byId(list);
        List<StaffTimeService.OnDuty> duty = staffTime.onDuty(storeId).stream()
                .filter(person -> byId.containsKey(person.userId())).toList();
        Map<UUID, Instant> seen = list.isEmpty() ? Map.of()
                : conversations.findSeen(ids(list)).stream().collect(Collectors.toMap(
                        ChatConversationRepository.Seen::getUserId, ChatConversationRepository.Seen::getLastSeenAt));
        Map<UUID, String> photos = avatarUrls(ids(list));
        Instant onlineSince = Instant.now().minus(ONLINE);
        Set<UUID> working = duty.stream().map(StaffTimeService.OnDuty::userId).collect(Collectors.toSet());
        List<ChatDtos.Working> workingList = duty.stream()
                .sorted(Comparator.comparing((StaffTimeService.OnDuty person) -> person.since() == null)
                        .thenComparing(person -> person.start(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(person -> byId.get(person.userId()).getDisplayName().toLowerCase()))
                .map(person -> {
                    Instant last = seen.get(person.userId());
                    return new ChatDtos.Working(person.userId(), byId.get(person.userId()).getDisplayName(),
                            photos.get(person.userId()), person.position(), hours ? person.start() : null,
                            hours ? person.end() : null, hours ? person.since() : null, person.since() != null,
                            last, last != null && last.isAfter(onlineSince));
                }).toList();
        List<ChatDtos.Other> others = list.stream().filter(user -> !working.contains(user.getId()))
                .map(user -> {
                    Instant last = seen.get(user.getId());
                    return new ChatDtos.Other(user.getId(), user.getDisplayName(), photos.get(user.getId()),
                            user.rankNameIn(storeId), last, last != null && last.isAfter(onlineSince));
                })
                .sorted(Comparator.comparing(ChatDtos.Other::online).reversed()
                        .thenComparing(ChatDtos.Other::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(other -> other.name().toLowerCase()))
                .toList();
        return new ChatDtos.People(hours, workingList, others);
    }

    // ---------------------------------------------------------------- dostęp

    private List<ChatConversation> visible(AppUser me) {
        List<UUID> storeIds = myStores(me).stream().map(Store::getId).toList();
        List<ChatConversation> result = new ArrayList<>();
        if (!storeIds.isEmpty()) {
            conversations.ensureStoreChats(storeIds);
            result.addAll(conversations.findStoreChats(storeIds));
        }
        // Grupa sklepu tylko póki konto ma dostęp do sklepu (odpięty albo przeniesiony traci jego grupy).
        conversations.findOfMember(me.getId()).stream()
                .filter(conversation -> conversation.getStore() == null
                        || storeIds.contains(conversation.getStore().getId()))
                .forEach(result::add);
        if (!result.isEmpty()) {
            conversations.startReading(result.stream().map(ChatConversation::getId).toList(), me.getId(),
                    Instant.now());
        }
        return result;
    }

    private ChatConversation visible(AppUser me, UUID conversationId) {
        ChatConversation conversation = conversations.findWithStore(conversationId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiej rozmowy"));
        boolean sees = switch (conversation.getKind()) {
            case STORE -> hasStore(me, conversation.getStore());
            case GROUP -> (conversation.getStore() == null || hasStore(me, conversation.getStore()))
                    && members.isMember(conversationId, me.getId());
            case DIRECT -> members.isMember(conversationId, me.getId());
        };
        if (!sees) {
            throw ApiException.notFound("Nie ma takiej rozmowy");
        }
        return conversation;
    }

    // ---------------------------------------------------------------- nazwa i logo

    @Transactional
    public ChatDtos.LogoChange setLogo(UUID userId, UUID conversationId, MultipartFile file) {
        AppUser me = user(userId);
        ChatConversation conversation = editable(me, conversationId);
        byte[] content = ScanFiles.content(file);
        String type = ScanFiles.contentType(content);
        if (type == null || !type.startsWith("image/")) {
            throw ApiException.badRequest("Logo to obrazek JPG, PNG albo WEBP");
        }
        if (content.length > MAX_LOGO_BYTES) {
            throw ApiException.badRequest("Logo może mieć najwyżej 2 MB");
        }
        UUID previous = logos.findActiveOf(conversationId).map(logo -> {
            logo.markDeleted(me);
            return logo.getId();
        }).orElse(null);
        logos.flush();
        logos.save(new ChatLogo(conversation, type, content, me));
        announce(conversation, audience(conversation), "conversation", Map.of());
        return new ChatDtos.LogoChange(summaries(List.of(conversation), me, storeId(conversation)).getFirst(),
                previous);
    }

    @Transactional
    public ChatDtos.LogoChange removeLogo(UUID userId, UUID conversationId) {
        AppUser me = user(userId);
        ChatConversation conversation = editable(me, conversationId);
        ChatLogo logo = logos.findActiveOf(conversationId)
                .orElseThrow(() -> ApiException.notFound("Ta rozmowa nie ma logo"));
        logo.markDeleted(me);
        announce(conversation, audience(conversation), "conversation", Map.of());
        return new ChatDtos.LogoChange(summaries(List.of(conversation), me, storeId(conversation)).getFirst(),
                logo.getId());
    }

    /** „Przywróć”: wcześniejsze logo wraca, a obecne (jeśli jest) dostaje znacznik. */
    @Transactional
    public ChatDtos.Conversation restoreLogo(UUID userId, UUID conversationId, UUID logoId) {
        AppUser me = user(userId);
        ChatConversation conversation = editable(me, conversationId);
        ChatLogo logo = logos.findOf(conversationId, logoId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego logo"));
        if (logo.getDeletedAt() != null) {
            logos.findActiveOf(conversationId).ifPresent(current -> current.markDeleted(me));
            logos.flush();
            logo.restore();
            announce(conversation, audience(conversation), "conversation", Map.of());
        }
        return summaries(List.of(conversation), me, storeId(conversation)).getFirst();
    }

    @Transactional(readOnly = true)
    public StoredFile logo(UUID userId, UUID conversationId, UUID logoId) {
        AppUser me = user(userId);
        visible(me, conversationId);
        ChatLogo logo = logos.findOf(conversationId, logoId).filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiego logo"));
        return new StoredFile("logo", logo.getContentType(), logo.getContent());
    }

    /** Nazwę i logo czatu sklepu albo grupy zmienia zarządzający czatem (rozmowa dwóch osób ich nie ma). */
    private boolean canEdit(ChatConversation conversation, AppUser me) {
        return conversation.getKind() != ChatKind.DIRECT && moderates(conversation, me);
    }

    private ChatConversation editable(AppUser me, UUID conversationId) {
        ChatConversation conversation = visible(me, conversationId);
        if (!canEdit(conversation, me)) {
            throw ApiException.forbidden("Nazwę i logo zmienia osoba z uprawnieniem do zarządzania czatem");
        }
        return conversation;
    }

    private static String logoUrl(UUID conversationId, UUID logoId) {
        return logoId == null ? null : "/api/chat/conversations/" + conversationId + "/logo/" + logoId;
    }

    private ChatConversation managed(AppUser me, UUID conversationId) {
        ChatConversation conversation = visible(me, conversationId);
        if (!canManage(conversation, me)) {
            throw ApiException.forbidden("Członków grupy zmienia osoba z uprawnieniem do zarządzania czatem");
        }
        return conversation;
    }

    private ChatMessage message(AppUser me, UUID messageId) {
        ChatMessage message = messages.findWithConversation(messageId)
                .orElseThrow(() -> ApiException.notFound("Nie ma takiej wiadomości"));
        visible(me, message.getConversation().getId());
        return message;
    }

    /**
     * Zarządzający czatem widzi treść cofniętych wiadomości i cofa cudze. Rozmowa dwóch osób jest prywatna: nikt jej
     * nie moderuje, każdy cofa tylko swoje. Grupę niezależną od sklepu prowadzi jej założyciel (i właściciel), a nie
     * każdy jej członek z uprawnieniem do czatu w jakimś sklepie.
     */
    private boolean moderates(ChatConversation conversation, AppUser me) {
        if (conversation.getKind() == ChatKind.DIRECT) {
            return false;
        }
        if (me.isOwner()) {
            return true;
        }
        if (conversation.getStore() != null) {
            return chatEdit(me, conversation.getStore().getId());
        }
        return createdBy(conversation, me);
    }

    private boolean canManage(ChatConversation conversation, AppUser me) {
        return conversation.getKind() == ChatKind.GROUP && moderates(conversation, me);
    }

    private boolean chatEdit(AppUser me, UUID storeId) {
        if (me.isOwner()) {
            return true;
        }
        StoreRole rank = me.rankIn(storeId);
        PermissionLevel level = rank == null ? null : rank.effectivePermissions().get(PermissionCategory.CHAT);
        return level != null && level.includes(PermissionLevel.EDIT);
    }

    private boolean anyChatEdit(AppUser me) {
        return me.isOwner() || myStores(me).stream().anyMatch(store -> chatEdit(me, store.getId()));
    }

    private static boolean createdBy(ChatConversation conversation, AppUser me) {
        return conversation.getCreatedBy() != null && conversation.getCreatedBy().getId().equals(me.getId());
    }

    private static boolean mine(ChatMessage message, AppUser me) {
        return message.getAuthor() != null && message.getAuthor().getId().equals(me.getId());
    }

    private static boolean canRestore(ChatMessage message, AppUser me) {
        return mine(message, me) && message.getRetractedBy() != null
                && message.getRetractedBy().getId().equals(me.getId());
    }

    private boolean hasStore(AppUser me, Store store) {
        return myStores(me).stream().anyMatch(mine -> mine.getId().equals(store.getId()));
    }

    private List<Store> myStores(AppUser me) {
        return me.isOwner() ? stores.findActiveOrdered()
                : me.getStores().stream().filter(Store::isActive).toList();
    }

    /** Osoby, które mogą być w rozmowie: ze sklepu albo (bez sklepu) z któregokolwiek z moich sklepów. */
    private List<AppUser> candidates(AppUser me, Store store) {
        Collection<UUID> storeIds = store != null ? List.of(store.getId())
                : myStores(me).stream().map(Store::getId).toList();
        return storeIds.isEmpty() ? List.of() : members.findUsersOfStores(storeIds);
    }

    /**
     * Członkowie rozmowy: przy czacie sklepu wszyscy z dostępem do sklepu, przy grupie sklepu tylko ci z członków,
     * którzy dalej mają dostęp do sklepu.
     */
    private List<AppUser> memberUsers(ChatConversation conversation) {
        if (conversation.getKind() == ChatKind.STORE) {
            return members.findUsersOfStores(List.of(conversation.getStore().getId()));
        }
        Set<UUID> withAccess = conversation.getStore() == null ? null
                : new HashSet<>(ids(members.findUsersOfStores(List.of(conversation.getStore().getId()))));
        return members.findActive(List.of(conversation.getId())).stream().map(ChatMember::getUser)
                .filter(user -> withAccess == null || withAccess.contains(user.getId()))
                .sorted(Comparator.comparing(user -> user.getDisplayName().toLowerCase())).toList();
    }

    /** Komu wysłać wiadomość na żywo o zmianie w rozmowie. */
    private Set<UUID> audience(ChatConversation conversation) {
        return new HashSet<>(ids(memberUsers(conversation)));
    }

    private void announce(ChatConversation conversation, Collection<UUID> userIds, String type,
            Map<String, ?> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("conversationId", conversation.getId());
        message.putAll(fields);
        live.publishToUsers(userIds, "chat", message);
    }

    // ---------------------------------------------------------------- odpowiedzi

    private List<ChatDtos.Conversation> summaries(List<ChatConversation> list, AppUser me, UUID currentStoreId) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(ChatConversation::getId).toList();
        Map<UUID, Set<UUID>> storeAccess = new HashMap<>();
        Map<UUID, ChatConversation> byConversation = list.stream()
                .collect(Collectors.toMap(ChatConversation::getId, Function.identity(), (a, b) -> a));
        Map<UUID, List<ChatMember>> membersOf = members.findActive(ids).stream()
                .filter(member -> {
                    Store store = byConversation.get(member.getConversation().getId()).getStore();
                    return store == null || storeAccess.computeIfAbsent(store.getId(),
                            id -> new HashSet<>(ids(members.findUsersOfStores(List.of(id)))))
                            .contains(member.getUser().getId());
                })
                .collect(Collectors.groupingBy(member -> member.getConversation().getId()));
        Map<UUID, Long> unread = conversations.countUnread(ids, me.getId()).stream().collect(Collectors.toMap(
                ChatConversationRepository.Unread::getConversationId, ChatConversationRepository.Unread::getUnread));
        Map<UUID, Long> unreadMentions = mentions.countUnread(ids, me.getId()).stream().collect(Collectors.toMap(
                ChatMentionRepository.UnreadMentions::getConversationId,
                ChatMentionRepository.UnreadMentions::getUnread));
        Map<UUID, UUID> logoOf = logos.findActive(ids).stream().collect(Collectors.toMap(
                ChatLogoRepository.Active::getConversationId, ChatLogoRepository.Active::getId, (a, b) -> a));
        Map<UUID, ChatConversationRepository.LastMessage> last = conversations.findLastMessages(ids).stream()
                .collect(Collectors.toMap(ChatConversationRepository.LastMessage::getConversationId,
                        Function.identity()));
        Set<UUID> local = new HashSet<>();
        if (currentStoreId != null) {
            local.addAll(members.findAssignedIds(currentStoreId));
        }
        Map<UUID, AppUser> peers = new HashMap<>();
        list.stream().filter(conversation -> conversation.getKind() == ChatKind.DIRECT).forEach(conversation ->
                membersOf.getOrDefault(conversation.getId(), List.of()).stream()
                        .map(ChatMember::getUser).filter(user -> !user.getId().equals(me.getId())).findFirst()
                        .ifPresent(peer -> peers.put(conversation.getId(), peer)));
        Map<UUID, String> photos = avatarUrls(peers.values().stream().map(AppUser::getId).toList());
        return list.stream().map(conversation -> {
            AppUser peerUser = peers.get(conversation.getId());
            ChatDtos.Person peer = peerUser == null ? null
                    : mapper.toPerson(peerUser, photos.get(peerUser.getId()));
            boolean isLocal = conversation.getKind() == ChatKind.DIRECT
                    ? peerUser != null && (local.contains(peerUser.getId()) || peerUser.isOwner())
                    : conversation.getStore() != null && conversation.getStore().getId().equals(currentStoreId);
            ChatConversationRepository.LastMessage lastMessage = last.get(conversation.getId());
            return mapper.toConversation(conversation, peer,
                    membersOf.getOrDefault(conversation.getId(), List.of()).size(), lastMessage(lastMessage),
                    unread.getOrDefault(conversation.getId(), 0L), unreadMentions.getOrDefault(conversation.getId(), 0L),
                    isLocal, canManage(conversation, me), canEdit(conversation, me),
                    logoUrl(conversation.getId(), logoOf.get(conversation.getId())));
        }).sorted(Comparator.comparing(ChatDtos.Conversation::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private static ChatDtos.LastMessage lastMessage(ChatConversationRepository.LastMessage last) {
        if (last == null) {
            return null;
        }
        boolean retracted = last.getRetractedAt() != null;
        String body = retracted ? null : last.getBody();
        if (body != null && body.length() > PREVIEW) {
            body = body.substring(0, PREVIEW) + "…";
        }
        return new ChatDtos.LastMessage(last.getAuthorId(), last.getAuthorName(), body,
                retracted ? 0 : last.getAttachmentCount(), retracted, last.getCreatedAt());
    }

    private List<ChatDtos.Message> toMessages(List<ChatMessage> list, AppUser me, boolean moderator,
            Map<UUID, String> photos) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> messageIds = list.stream().map(ChatMessage::getId).toList();
        Map<UUID, List<ChatDtos.Mention>> mentionsOf = mentions.findOfMessages(messageIds).stream()
                .collect(Collectors.groupingBy(mention -> mention.getMessage().getId(), Collectors.mapping(
                        mention -> new ChatDtos.Mention(mention.getKind(), mention.getTargetId(), mention.getLabel()),
                        Collectors.toList())));
        Set<UUID> mentioningMe = new HashSet<>(mentions.findMentioning(messageIds, me.getId()));
        Map<UUID, List<ChatDtos.Attachment>> files = attachments.findOfMessages(messageIds).stream()
                .collect(Collectors.groupingBy(ChatAttachmentRepository.Summary::getMessageId,
                        Collectors.mapping(mapper::toAttachment, Collectors.toList())));
        List<UUID> replyIds = list.stream().map(ChatMessage::getReplyTo).filter(Objects::nonNull)
                .map(ChatMessage::getId).distinct().toList();
        Map<UUID, Long> replyFiles = replyIds.isEmpty() ? Map.of()
                : attachments.findOfMessages(replyIds).stream().collect(Collectors.groupingBy(
                        ChatAttachmentRepository.Summary::getMessageId, Collectors.counting()));
        return list.stream().map(message -> {
            boolean retracted = message.getRetractedAt() != null;
            boolean hidden = retracted && !moderator;
            UUID authorId = message.getAuthor() == null ? null : message.getAuthor().getId();
            return mapper.toMessage(message, hidden ? null : message.getBody(),
                    authorId == null ? null : photos.get(authorId), retracted,
                    hidden ? List.of() : files.getOrDefault(message.getId(), List.of()),
                    hidden ? List.of() : mentionsOf.getOrDefault(message.getId(), List.of()),
                    !retracted && mentioningMe.contains(message.getId()),
                    !retracted && mine(message, me), retracted && canRestore(message, me),
                    hidden ? null : reply(message.getReplyTo(), replyFiles));
        }).toList();
    }

    private static ChatDtos.Reply reply(ChatMessage original, Map<UUID, Long> files) {
        if (original == null) {
            return null;
        }
        boolean retracted = original.getRetractedAt() != null;
        String body = retracted ? null : original.getBody();
        if (body != null && body.length() > PREVIEW) {
            body = body.substring(0, PREVIEW) + "…";
        }
        AppUser author = original.getAuthor();
        return new ChatDtos.Reply(original.getId(), author == null ? null : author.getId(),
                author == null ? null : author.getDisplayName(), body,
                retracted ? 0 : files.getOrDefault(original.getId(), 0L).intValue(), retracted);
    }

    private Map<UUID, String> avatarUrls(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        avatars.findSummaries(userIds).forEach(avatar -> result.put(avatar.getUserId(),
                images.url(avatar.getStorageKey(), "/api/auth/avatar/" + avatar.getId())));
        return result;
    }

    private static List<UUID> authorIds(ChatMessage message) {
        return message.getAuthor() == null ? List.of() : List.of(message.getAuthor().getId());
    }

    private static UUID storeId(ChatConversation conversation) {
        return conversation.getStore() == null ? null : conversation.getStore().getId();
    }

    private static List<UUID> ids(Collection<AppUser> list) {
        return list.stream().map(AppUser::getId).toList();
    }

    private static Map<UUID, AppUser> byId(Collection<AppUser> list) {
        Map<UUID, AppUser> result = new LinkedHashMap<>();
        list.forEach(user -> result.put(user.getId(), user));
        return result;
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "plik";
        }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        return name.isEmpty() ? "plik" : name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private AppUser user(UUID userId) {
        return users.findWithRoleById(userId).orElseThrow(() -> ApiException.notFound("Nie ma takiego konta"));
    }
}
