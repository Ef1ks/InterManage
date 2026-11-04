package com.intermanage.backend.leaflet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Artykuł z kartoteki (ITM8) wybrany dla EAN-u z gazetki. */
public record LeafletLinkRequest(
        @NotBlank @Pattern(regexp = "\\d{1,20}", message = "EAN z gazetki to same cyfry") String ean,
        @NotBlank @Pattern(regexp = "\\d{1,13}", message = "ITM8 to same cyfry") String itm8) {
}
