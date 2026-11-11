package com.intermanage.backend.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.component.UserImages;
import com.intermanage.backend.auth.dto.AvatarChangeResponse;
import com.intermanage.backend.auth.dto.AvatarResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.UserAvatar;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.UserAvatarRepository;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Zdjęcie profilowe konta. Każde konto zmienia tylko swoje. Nowe zdjęcie i usunięcie dają poprzedniemu znacznik,
 * więc „Przywróć” wraca do niego.
 */
@Service
public class UserAvatarService {

    private final UserAvatarRepository avatars;
    private final AppUserRepository users;
    private final UserImages images;

    public UserAvatarService(UserAvatarRepository avatars, AppUserRepository users, UserImages images) {
        this.avatars = avatars;
        this.users = users;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public AvatarResponse current(UUID userId) {
        return avatars.findSummary(userId).map(row -> response(row.getId(), row.getStorageKey())).orElse(null);
    }

    @Transactional
    public AvatarChangeResponse upload(UUID userId, MultipartFile file) {
        AppUser user = user(userId);
        UserImages.Stored image = images.store(file, "avatars", userId);
        UserAvatar previous = avatars.findCurrent(userId).orElse(null);
        if (previous != null) {
            previous.markRemoved(user);
            // Najpierw znacznik, potem nowy wpis: jedno obowiązujące zdjęcie pilnuje indeks w bazie.
            avatars.flush();
        }
        UserAvatar saved = avatars.save(new UserAvatar(user, image));
        return new AvatarChangeResponse(summary(saved), previous == null ? null : previous.getId());
    }

    @Transactional
    public AvatarChangeResponse remove(UUID userId) {
        UserAvatar current = avatars.findCurrent(userId)
                .orElseThrow(() -> ApiException.notFound("Nie masz ustawionego zdjęcia profilowego"));
        current.markRemoved(user(userId));
        return new AvatarChangeResponse(null, current.getId());
    }

    @Transactional
    public AvatarChangeResponse restore(UUID userId, UUID id) {
        UserAvatar restored = avatars.findOwn(id, userId)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zdjęcia"));
        if (!restored.isRemoved()) {
            return new AvatarChangeResponse(summary(restored), null);
        }
        UserAvatar current = avatars.findCurrent(userId).orElse(null);
        if (current != null) {
            current.markRemoved(user(userId));
            avatars.flush();
        }
        restored.restore();
        return new AvatarChangeResponse(summary(restored), current == null ? null : current.getId());
    }

    /**
     * Plik zdjęcia zapisanego w bazie, dla każdego zalogowanego (zdjęcie profilowe widzą inni). Zdjęcie z R2 ma adres
     * na CDN, więc tu go nie ma.
     */
    @Transactional(readOnly = true)
    public UserAvatar file(UUID id) {
        return avatars.findById(id).filter(avatar -> avatar.getContent() != null)
                .orElseThrow(() -> ApiException.notFound("Nie znaleziono zdjęcia"));
    }

    private AppUser user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
    }

    private AvatarResponse summary(UserAvatar avatar) {
        return response(avatar.getId(), avatar.getStorageKey());
    }

    private AvatarResponse response(UUID id, String storageKey) {
        return new AvatarResponse(id, images.url(storageKey, "/api/auth/avatar/" + id));
    }
}
