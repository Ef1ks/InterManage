package com.intermanage.backend.chat.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.security.Access;
import com.intermanage.backend.auth.security.AppUserPrincipal;
import com.intermanage.backend.chat.dto.ChatDtos;
import com.intermanage.backend.chat.service.ChatService;

import jakarta.validation.Valid;

/**
 * Czat firmowy: {@code /api/chat/**} dla każdego zalogowanego (rozmowy nie należą do jednego sklepu, dostęp sprawdza
 * {@link ChatService}), panel osób i lista do wyboru pod {@code /api/stores/{storeId}/chat}.
 */
@RestController
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @GetMapping("/api/chat/conversations")
    public List<ChatDtos.Conversation> conversations(@RequestParam(required = false) UUID storeId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.conversations(principal.getId(), storeId);
    }

    @GetMapping("/api/chat/unread")
    public ChatDtos.Unread unread(@AuthenticationPrincipal AppUserPrincipal principal) {
        return service.unread(principal.getId());
    }

    @PostMapping("/api/chat/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.Conversation createGroup(@Valid @RequestBody ChatDtos.GroupRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.createGroup(principal.getId(), request);
    }

    @PutMapping("/api/chat/conversations/{id}/name")
    public ChatDtos.Conversation rename(@PathVariable UUID id, @Valid @RequestBody ChatDtos.RenameRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.rename(principal.getId(), id, request);
    }

    /** Logo czatu sklepu albo grupy (JPG, PNG, WEBP do 2 MB); zmienia zarządzający czatem. */
    @PutMapping(path = "/api/chat/conversations/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatDtos.LogoChange setLogo(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setLogo(principal.getId(), id, file);
    }

    @DeleteMapping("/api/chat/conversations/{id}/logo")
    public ChatDtos.LogoChange removeLogo(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.removeLogo(principal.getId(), id);
    }

    @PostMapping("/api/chat/conversations/{id}/logo/{logoId}/restore")
    public ChatDtos.Conversation restoreLogo(@PathVariable UUID id, @PathVariable UUID logoId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restoreLogo(principal.getId(), id, logoId);
    }

    /** Logo dla każdego, kto widzi rozmowę; adres zmienia się z każdym nowym logo, więc może leżeć w pamięci. */
    @GetMapping("/api/chat/conversations/{id}/logo/{logoId}")
    public ResponseEntity<byte[]> logo(@PathVariable UUID id, @PathVariable UUID logoId,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ChatService.StoredFile file = service.logo(principal.getId(), id, logoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PutMapping("/api/chat/conversations/{id}/members")
    public ChatDtos.Conversation setMembers(@PathVariable UUID id, @Valid @RequestBody ChatDtos.MembersRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.setMembers(principal.getId(), id, request);
    }

    @PostMapping("/api/chat/direct")
    public ChatDtos.Conversation direct(@Valid @RequestBody ChatDtos.DirectRequest request,
            @RequestParam(required = false) UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.direct(principal.getId(), request.userId(), storeId);
    }

    @GetMapping("/api/chat/conversations/{id}/messages")
    public ChatDtos.Thread thread(@PathVariable UUID id, @RequestParam(required = false) Instant before,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.thread(principal.getId(), id, before);
    }

    /**
     * Multipart: {@code body}, {@code files} (razem do 10 MB, najwyżej 10) i {@code mentions} ({@code user:<id>},
     * {@code department:<id>}, {@code all}, powtarzane).
     */
    @PostMapping(path = "/api/chat/conversations/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.Message send(@PathVariable UUID id, @RequestParam(required = false) String body,
            @RequestParam(name = "files", required = false) List<MultipartFile> files,
            @RequestParam(name = "mentions", required = false) List<String> mentions,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.send(principal.getId(), id, body, files, mentions);
    }

    /** Sam tekst jako JSON {@code {body}} (aplikacja mobilna). */
    @PostMapping(path = "/api/chat/conversations/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.Message sendText(@PathVariable UUID id, @RequestBody ChatDtos.TextRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.send(principal.getId(), id, request.body(), List.of(), request.mentions());
    }

    @PostMapping("/api/chat/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        service.markRead(principal.getId(), id);
    }

    @PostMapping("/api/chat/conversations/{id}/typing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void typing(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        service.typing(principal.getId(), id);
    }

    @PostMapping("/api/chat/messages/{id}/retract")
    public ChatDtos.Message retract(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.retract(principal.getId(), id);
    }

    @PostMapping("/api/chat/messages/{id}/restore")
    public ChatDtos.Message restore(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.restore(principal.getId(), id);
    }

    /** Plik zawsze do pobrania ({@code attachment}, {@code nosniff}): treść od użytkownika nie otwiera się w aplikacji. */
    @GetMapping("/api/chat/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable UUID id,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        ChatService.StoredFile file = service.attachment(principal.getId(), id);
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.contentType());
        } catch (RuntimeException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(type)
                .body(file.content());
    }

    /** Przeglądarka i telefon zgłaszają się co minutę, gdy ktoś z nich korzysta („online” i „ostatnio …”). */
    @PostMapping("/api/chat/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void presence(@AuthenticationPrincipal AppUserPrincipal principal) {
        service.touchPresence(principal.getId());
    }

    @GetMapping("/api/stores/{storeId}/chat/people")
    @PreAuthorize(Access.STORE)
    public ChatDtos.People people(@PathVariable UUID storeId, @AuthenticationPrincipal AppUserPrincipal principal) {
        // Godziny zmian i odbić to dane Czasu pracy: bez dostępu do niego panel mówi tylko, kto jest w pracy.
        return service.people(storeId, principal.administers() || principal.has("STAFF_TIME:VIEW"));
    }

    /** Osoby do nowej rozmowy albo grupy: ze sklepu albo ({@code company=true}) ze wszystkich moich sklepów. */
    @GetMapping("/api/stores/{storeId}/chat/candidates")
    @PreAuthorize(Access.STORE)
    public List<ChatDtos.Person> candidates(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "false") boolean company,
            @AuthenticationPrincipal AppUserPrincipal principal) {
        return service.candidates(principal.getId(), storeId, company);
    }
}
