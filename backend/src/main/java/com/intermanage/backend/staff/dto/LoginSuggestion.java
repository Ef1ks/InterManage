package com.intermanage.backend.staff.dto;

/** Wolny login dla imienia i nazwiska; pusty, gdy nie da się go złożyć (trzeba wpisać ręcznie). */
public record LoginSuggestion(String username) {
}
