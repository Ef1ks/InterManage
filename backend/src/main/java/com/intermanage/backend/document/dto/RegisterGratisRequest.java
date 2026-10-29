package com.intermanage.backend.document.dto;

import java.time.YearMonth;
import java.util.UUID;

import com.intermanage.backend.document.entity.GratisKind;

import jakarta.validation.constraints.NotNull;

/**
 * Dostawca, od którego jest gratis (z listy dostawców sklepu), za co jest gratis i miesiąc, którego dotyczy
 * ({@code reportMonth} pusty = miesiąc daty dokumentu).
 */
public record RegisterGratisRequest(
        @NotNull(message = "Wybierz dostawcę") UUID supplierId,
        @NotNull(message = "Wybierz, za co jest gratis") GratisKind kind,
        YearMonth reportMonth) {
}
