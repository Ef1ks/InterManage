package com.intermanage.backend.auth.dto;

import java.util.UUID;

/**
 * Wynik zmiany zdjęcia profilowego: obowiązujące teraz ({@code null} = bez zdjęcia) i to, które przestało obowiązywać,
 * do „Przywróć” w toaście ({@code null}, gdy wcześniej nie było żadnego).
 */
public record AvatarChangeResponse(AvatarResponse avatar, UUID previousId) {
}
