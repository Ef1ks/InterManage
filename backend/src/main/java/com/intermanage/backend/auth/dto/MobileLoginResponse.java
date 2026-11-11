package com.intermanage.backend.auth.dto;

/** Token dla aplikacji mobilnej ({@code Authorization: Bearer}) i zalogowany użytkownik. */
public record MobileLoginResponse(String token, MeResponse me) {
}
