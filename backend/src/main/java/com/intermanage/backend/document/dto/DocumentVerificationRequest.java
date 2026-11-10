package com.intermanage.backend.document.dto;

import com.intermanage.backend.document.entity.DocumentVerificationStatus;

import jakarta.validation.constraints.NotNull;

/** Nowy status weryfikacji przyjęcia PZ. */
public record DocumentVerificationRequest(@NotNull(message = "Wybierz status weryfikacji") DocumentVerificationStatus status) {
}
