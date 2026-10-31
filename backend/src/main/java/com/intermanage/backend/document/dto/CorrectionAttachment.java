package com.intermanage.backend.document.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Korekta od dostawcy (WZ) przypięta sama do przyjęcia PZ, którego fakturę koryguje jej faktura korygująca. */
public record CorrectionAttachment(@NotNull UUID deliveryId, @NotNull UUID correctionId) {
}
