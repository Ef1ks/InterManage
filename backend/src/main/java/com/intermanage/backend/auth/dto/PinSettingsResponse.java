package com.intermanage.backend.auth.dto;

/** Ustawienia przełączania na to konto: czy ma PIN i czy przejście go wymaga. */
public record PinSettingsResponse(boolean hasPin, boolean pinRequired) {
}
