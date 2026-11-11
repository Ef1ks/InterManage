package com.intermanage.backend.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.intermanage.backend.auth.component.UserImages;
import com.intermanage.backend.auth.dto.BackgroundChangeResponse;
import com.intermanage.backend.auth.dto.BackgroundResponse;
import com.intermanage.backend.auth.entity.AppUser;
import com.intermanage.backend.auth.entity.UserBackground;
import com.intermanage.backend.auth.repository.AppUserRepository;
import com.intermanage.backend.auth.repository.UserBackgroundRepository;
import com.intermanage.backend.common.exception.ApiException;

/**
 * Własne zdjęcie w tle aplikacji, zapisane przy koncie. Każde konto zmienia tylko swoje. Nowe zdjęcie i usunięcie
 * dają poprzedniemu znacznik, więc „Przywróć” wraca do niego.
 */
@Service
public class UserBackgroundService {

    private final UserBackgroundRepository backgrounds;
    private final AppUserRepository users;
    private final UserImages images;

    public UserBackgroundService(UserBackgroundRepository backgrounds, AppUserRepository users, UserImages images) {
        this.backgrounds = backgrounds;
        this.users = users;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public BackgroundResponse current(UUID userId) {
        return backgrounds.findSummary(userId)
                .map(row -> response(row.getId(), row.getStorageKey(), row.getVeilPercent()))
                .orElse(null);
    }

    @Transactional
    public BackgroundChangeResponse upload(UUID userId, MultipartFile file) {
        AppUser user = user(userId);
        UserImages.Stored image = images.store(file, "backgrounds", userId);
        UserBackground previous = backgrounds.findCurrent(userId).orElse(null);
        int veil = UserBackground.DEFAULT_VEIL;
        if (previous != null) {
            veil = previous.getVeilPercent();
            previous.markRemoved(user);
            // Najpierw znacznik, potem nowy wpis: jedno obowiązujące zdjęcie pilnuje indeks w bazie.
            backgrounds.flush();
        }
        UserBackground saved = backgrounds.save(new UserBackground(user, image, veil));
        return new BackgroundChangeResponse(summary(saved), previous == null ? null : previous.getId());
    }

    @Transactional
    public BackgroundChangeResponse changeVeil(UUID userId, int veilPercent) {
        UserBackground current = backgrounds.findCurrent(userId)
                .orElseThrow(() -> ApiException.notFound("Nie masz ustawionego zdjęcia w tle"));
        current.changeVeil(veilPercent);
        return new BackgroundChangeResponse(summary(current), null);
    }

    @Transactional
    public BackgroundChangeResponse remove(UUID userId) {
        UserBackground current = backgrounds.findCurrent(userId)
                .orElseThrow(() -> ApiException.notFound("Nie masz ustawionego zdjęcia w tle"));
        current.markRemoved(user(userId));
        return new BackgroundChangeResponse(null, current.getId());
    }

    @Transactional
    public BackgroundChangeResponse restore(UUID userId, UUID id) {
        UserBackground restored = own(userId, id);
        if (!restored.isRemoved()) {
            return new BackgroundChangeResponse(summary(restored), null);
        }
        UserBackground current = backgrounds.findCurrent(userId).orElse(null);
        if (current != null) {
            current.markRemoved(user(userId));
            backgrounds.flush();
        }
        restored.restore();
        return new BackgroundChangeResponse(summary(restored), current == null ? null : current.getId());
    }

    /**
     * Plik zdjęcia zapisanego w bazie; także zastąpionego, bo adres z id nie zmienia się i przeglądarka trzyma go
     * w pamięci. Zdjęcie z R2 ma adres na CDN, więc tu go nie ma.
     */
    @Transactional(readOnly = true)
    public UserBackground file(UUID userId, UUID id) {
        UserBackground background = own(userId, id);
        if (background.getContent() == null) {
            throw ApiException.notFound("Nie znaleziono zdjęcia");
        }
        return background;
    }

    private UserBackground own(UUID userId, UUID id) {
        return backgrounds.findOwn(id, userId).orElseThrow(() -> ApiException.notFound("Nie znaleziono zdjęcia"));
    }

    private AppUser user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("Nie znaleziono użytkownika"));
    }

    private BackgroundResponse summary(UserBackground background) {
        return response(background.getId(), background.getStorageKey(), background.getVeilPercent());
    }

    private BackgroundResponse response(UUID id, String storageKey, int veilPercent) {
        return new BackgroundResponse(id, images.url(storageKey, "/api/auth/background/" + id), veilPercent);
    }
}
